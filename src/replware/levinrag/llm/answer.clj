(ns replware.levinrag.llm.answer
  "Answer generation: search → prompt → chat → strip
   <think> → validate [n] citations. Supports model-primary fallback mode."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [replware.levinrag.retrieval.pipeline :as pipeline]))

(def default-opts
  {:temperature 0.2
   :max-tokens 1024
   :extra-body nil})

(def empty-answer-message "模型沒有產生回答，請稍後再試。")

(defn prompt
  "System prompt for RAG mode with sources."
  []
  (slurp (io/resource "prompts/answer.md")))

(defn general-prompt
  "System prompt when no sources are retrieved."
  []
  (if-let [res (io/resource "prompts/general.md")]
    (slurp res)
    "你是 AI 助理。請運用自身知識解答使用者的問題。"))

(defn strip-think [s] ... ) ;; 保持原樣

(defn parse-citations [s n] ... ) ;; 保持原樣

(defn- neutralize [s] ... ) ;; 保持原樣

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

(defn- content [resp] ... ) ;; 保持原樣

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
        sys-prompt (if has-passages? (prompt) (general-prompt))
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