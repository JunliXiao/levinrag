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

(defn rag-prompt
  "System prompt for RAG mode with sources."
  []
  (slurp (io/resource "prompts/answer_rag.md")))

(defn general-prompt
  "System prompt when no sources are retrieved."
  []
  (if-let [res (io/resource "prompts/answer_general.md")]
    (slurp res)
    "你是 AI 助理。請運用自身知識解答使用者的問題。"))

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
  "Search as `principal`, then answer. If passages exist, perform citation RAG;
   otherwise, fall back to general LLM response."
  [{:keys [search-fn chat-fn]
    :or {search-fn pipeline/search}
    :as deps} principal query opts]
  (let [res (search-fn deps principal query opts)
        passages (:passages res)
        has-passages? (boolean (seq passages))
        base {:candidates (:candidates res)
              :degraded (:degraded res)}
        
        ;; 1. 選擇 Prompt 與構建 Messages
        sys-prompt (if has-passages? (rag-prompt) (general-prompt))
        input-msgs (messages sys-prompt passages query)
        
        t0 (System/nanoTime)
        resp (chat-fn input-msgs
                      (merge default-opts
                             (into {} (for [k (keys default-opts)
                                            :let [ck (keyword "chat" (name k))]
                                            :when (contains? opts ck)]
                                        [k (opts ck)]))))
        ms (quot (- (System/nanoTime) t0) 1000000)
        
        ;; 2. 解析內文與引用標籤
        raw-text (strip-think (content resp))
        {:keys [text cited invalid]} (if has-passages?
                                       (parse-citations raw-text (count passages))
                                       {:text raw-text :cited [] :invalid []})
        
        blank? (str/blank? text)
        by-n (into {} (map (juxt :n identity)) passages)
        
        ;; 3. 標記狀態標籤
        flags (cond-> (get-in res [:stages :flags] #{})
                blank? (conj :empty-answer)
                (not has-passages?) (conj :fallback-parametric-answer)
                (and has-passages? (not blank?) (empty? cited) (not (re-find not-found-re text))) (conj :uncited-answer))]
    
    (assoc base
           :answer (if blank? empty-answer-message text)
           :citations (if (or blank? (not has-passages?)) [] (mapv by-n cited))
           :no-evidence? (not has-passages?)
           :stages (assoc (:stages res)
                          :flags flags
                          :generate {:ms ms
                                     :model (:model resp)
                                     :prompt-tokens (get-in resp [:usage :prompt_tokens])
                                     :completion-tokens (get-in resp [:usage :completion_tokens])
                                     :finish-reason (get-in resp [:choices 0 :finish_reason])
                                     :invalid-citations invalid}))))
