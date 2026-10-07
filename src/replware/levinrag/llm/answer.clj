(ns replware.levinrag.llm.answer
  "Answer generation: search → prompt → chat → strip
   <think> → validate [n] citations. Supports model-primary fallback mode."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [replware.levinrag.retrieval.pipeline :as pipeline]))

(def default-opts
  {:temperature 0.5
   :max-tokens 4096
   :extra-body nil})

(def empty-answer-message "模型沒有產生回答，請稍後再試。")
(def no-evidence-message "在你有權限存取的資料中找不到相關內容。")

(def modes
  "問題模式"
  ;; 裡面不能有單引號或反斜線，不然拼出來的 JS 字串會斷掉，Alpine 會報錯，整個元素就無法動
  {:rag     {:id "rag"     :label "內部知識" :desc "檢索內部語料"}
   :general {:id "general" :label "通用知識" :desc "不連接內部語料"}})

(defn rag-prompt
  "System prompt for RAG mode with sources."
  []
  (slurp (io/resource "prompts/answer_rag.md")))

(defn general-prompt
  "System prompt when no sources are retrieved."
  []
  (slurp (io/resource "prompts/answer_general.md")))

(defn strip-think
  "Remove <think>…</think> blocks; an unclosed <think> drops the rest; a
   leftover </think> with no opening tag (templates that pre-fill
   <think>) drops everything before it."
  [s]
  (-> s
      (str/replace #"(?s)<think>.*?</think>" "")
      (str/replace #"(?s)<think>.*\z" "")
      (str/replace #"(?s)\A.*?</think>" "")
      str/trim))

(def ^:private citation-re
  ;; [1] [1, 3] ［1］ 【1】 ［１］, but not a Markdown link text [..](..)
  ;; \x28 is an open paren, spelled out so the pre-commit hook's raw
  ;; bracket count stays even; (?U) lets \d match full-width digits
  #"(?U)[\[［【]\s*(\d+(?:\s*[,，、]\s*\d+)*)\s*[\]］】](?!\x28)")

(defn- numbers
  "Integers in a citation group (full-width digits are NFKC-normalized);
   one too long for a long counts as 0 (invalid)."
  [group]
  (mapv #(or (parse-long (java.text.Normalizer/normalize % java.text.Normalizer$Form/NFKC)) 0)
        (re-seq #"(?U)\d+" group)))

(defn parse-citations
  "Citations in `s` given `n` passages. Returns {:text :cited :invalid}:
   :text has every citation rewritten as [n] and out-of-range ones
   removed; :cited and :invalid are distinct, ascending."
  [s n]
  (let [valid? #(<= 1 % n)
        found (mapcat (comp numbers second) (re-seq citation-re s))]
    {:text (str/replace s citation-re
                        (fn [[_ group]] (apply str (map #(str "[" % "]") (filter valid? (numbers group))))))
     :cited (vec (sort (distinct (filter valid? found))))
     :invalid (vec (sort (distinct (remove valid? found))))}))

(defn- neutralize
  "Document text with <sources>/</sources> (any case, whitespace inside
   the tag) turned into full-width brackets, so a document cannot close
   the sources block and address the model as the user (indirect prompt
   injection)."
  [s]
  (str/replace (str s) #"(?i)<(\s*/?\s*sources\s*)>" "＜$1＞"))

(defn messages
  "Chat messages builder handling both RAG and direct chat modes."
  [system passages query]
  [{:role "system"
    :content system}
   {:role "user"
    :content (if (seq passages)
               (str "<sources>\n"
                    (str/join "\n\n" (for [{:keys [n text] :as p} passages]
                                       (str "[" n "] " (neutralize (:doc/title p)) "｜" (neutralize (:section/trail p))
                                            "\n" (neutralize text))))
                    "\n</sources>\n\n問題：" query)
               query)}])

(def ^:private not-found-re #"找不到|查無|沒有相關|(?i)not found|no relevant|cannot find")

(defn- content
  "The reply text. A null content (reasoning servers return it when
   thinking used up max_tokens) is an empty reply; a response without a
   message (e.g. HTTP 200 with an error payload) is a chat dependency
   failure."
  [resp]
  (let [msg (get-in resp [:choices 0 :message])
        c (:content msg)]
    (cond
      (string? c) c
      (and (map? msg) (nil? c)) ""
      :else
      (throw (ex-info "chat response missing choices[0].message.content"
                      {:llm/endpoint :chat
                       :http/status 200
                       :llm/body-excerpt (let [s (pr-str resp)] (subs s 0 (min 500 (count s))))})))))

(defn ask!
  "Branching:
   - mode 'general': skips retrieval (no embed, no rerank), queries LLM directly with general prompt.
   - mode 'rag' (default): standard LevinRAG pipeline with no-evidence handling."
  [{:keys [search-fn chat-fn]
    :or {search-fn pipeline/search}
    :as deps} principal query opts]
  (let [mode (if (= (get opts :mode) "general") :general :rag)]
    (if (= mode :general)
      ;; --- 通用知識分支 (免去 embed & rerank) ---
      (let [t0 (System/nanoTime)
            resp (chat-fn [{:role "system" :content (general-prompt)}
                           {:role "user" :content query}]
                          (merge default-opts
                                 (into {} (for [k (keys default-opts)
                                                :let [ck (keyword "chat" (name k))]
                                                :when (contains? opts ck)]
                                            [k (opts ck)]))))
            ms (quot (- (System/nanoTime) t0) 1000000)
            text (strip-think (content resp))
            blank? (str/blank? text)]
        {:answer (if blank? empty-answer-message text)
         :citations []
         :candidates []
         :no-evidence? false
         :degraded #{}
         :stages {:flags (if blank? #{:empty-answer} #{})
                  :mode :general
                  :generate {:ms ms
                             :model (:model resp)
                             :prompt-tokens (get-in resp [:usage :prompt_tokens])
                             :completion-tokens (get-in resp [:usage :completion_tokens])
                             :finish-reason (get-in resp [:choices 0 :finish_reason])
                             :invalid-citations []}}})

      ;; --- 內部 RAG 分支 (既有標準流程) ---
      (let [res (search-fn deps principal query opts)
            passages (:passages res)
            base {:candidates (:candidates res)
                  :degraded (:degraded res)}]
        (if (empty? passages)
          (assoc base
                 :answer no-evidence-message
                 :citations []
                 :no-evidence? true
                 :stages (assoc (:stages res) :mode :rag))
          (let [t0 (System/nanoTime)
                resp (chat-fn (messages (rag-prompt) passages query)
                              (merge default-opts
                                     (into {} (for [k (keys default-opts)
                                                    :let [ck (keyword "chat" (name k))]
                                                    :when (contains? opts ck)]
                                                [k (opts ck)]))))
                ms (quot (- (System/nanoTime) t0) 1000000)
                {:keys [text cited invalid]} (parse-citations (strip-think (content resp)) (count passages))
                blank? (str/blank? text)
                by-n (into {} (map (juxt :n identity)) passages)
                flags (cond-> (get-in res [:stages :flags] #{})
                        blank? (conj :empty-answer)
                        (and (not blank?) (empty? cited) (not (re-find not-found-re text))) (conj :uncited-answer))]
            (assoc base
                   :answer (if blank? empty-answer-message text)
                   :citations (if blank? [] (mapv by-n cited))
                   :no-evidence? false
                   :stages (assoc (:stages res)
                                  :flags flags
                                  :mode :rag
                                  :generate {:ms ms
                                             :model (:model resp)
                                             :prompt-tokens (get-in resp [:usage :prompt_tokens])
                                             :completion-tokens (get-in resp [:usage :completion_tokens])
                                             :finish-reason (get-in resp [:choices 0 :finish_reason])
                                             :invalid-citations invalid}))))))))
