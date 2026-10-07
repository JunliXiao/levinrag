(ns replware.levinrag.web.layout
  "Page shell for the web UI: head, assets, CSRF token for HTMX and the
   top navigation."
  (:require [hiccup2.core :as hiccup]
            [jsonista.core :as json]
            [manifest-edn.core :as manifest]
            [reitit-extras.core :as reitit-extras]
            [replware.levinrag.llm.answer :as answer]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.util.response :as response]))

(defn csrf-token
  "The request's CSRF token; nil outside the anti-forgery middleware
   (router fallback pages)."
  []
  (when (bound? #'anti-forgery/*anti-forgery-token*)
    (force anti-forgery/*anti-forgery-token*)))

(defn csrf-field []
  [:input {:type "hidden"
           :name "__anti-forgery-token"
           :value (csrf-token)}])

(defn- nav [{:keys [principal]}]
  (when principal
    [:nav {:class ["flex" "items-center" "gap-4" "px-6" "py-3" "border-b" "border-slate-200" "bg-white"]}
     [:a {:href "/"
          :class ["font-semibold" "text-slate-900"]} "LevinRAG"]
     [:a {:href "/"
          :class ["text-sm" "text-slate-600" "hover:text-slate-900"]} "問答"]
     (when (:admin? principal)
       [:a {:href "/admin"
            :class ["text-sm" "text-slate-600" "hover:text-slate-900"]} "管理"])
     [:div {:class ["ml-auto" "flex" "items-center" "gap-4"]}
      ;; Toggle 按鈕：[toggle] -> [username] -> [登出]
      [:button {:type "button"
                :class ["flex" "items-center" "gap-2" "rounded-full" "border" "px-3" "py-1" "text-xs" "font-medium" "transition-colors"]
                "@click" "mode = (mode === 'rag' ? 'general' : 'rag')"
                ":class" "mode === 'rag' ? 'bg-sky-50 text-sky-700 border-sky-300 hover:bg-sky-100' : 'bg-purple-50 text-purple-700 border-purple-300 hover:bg-purple-100'"}
       [:span {:class ["inline-block" "h-2" "w-2" "rounded-full"]
               ":class" "mode === 'rag' ? 'bg-sky-500' : 'bg-purple-500'"}]
       [:span {:x-text "mode === 'rag' ? '內部知識' : '通用知識'"}]]
      [:span {:class ["text-sm" "text-slate-500"]} (:username principal)]
      [:form {:method "post"
              :action "/logout"}
       (csrf-field)
       [:button {:type "submit"
                 :class ["text-sm" "text-slate-600" "hover:text-slate-900"]} "登出"]]]]))

(defn page
  "Full HTML page (hiccup) with `title` and `body` for `request`."
  [request title & body]
  [:html {:lang "zh-Hant"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1"}]
    [:meta {:name "csrf-token"
            :content (csrf-token)}]
    [:link {:rel "icon"
            :href (manifest/asset "images/icon.svg")
            :type "image/svg+xml"}]
    [:link {:rel "stylesheet"
            :type "text/css"
            :href (manifest/asset "css/output.css")}]
    [:title (str title " · LevinRAG")]
    ;; KaTeX LaTeX 公式渲染支援
    [:link {:rel "stylesheet"
            :href "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css"}]
    [:script {:src "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"
              :defer true}]
    [:script {:src "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"
              :defer true}]]
   [:body {:x-data "{ mode: 'rag' }"
           :class ["min-h-screen" "bg-slate-50" "text-slate-800"]
           :hx-headers (when-let [t (csrf-token)] (json/write-value-as-string {"X-CSRF-Token" t}))}
    (nav request)
    [:main {:class ["mx-auto" "max-w-5xl" "px-6" "py-8"]} body]
    [:script {:src (manifest/asset "js/htmx.min.js")
              :defer true}]
    [:script {:src (manifest/asset "js/alpinejs.min.js")
              :defer true}]
    [:script {:src (manifest/asset "js/app.js")
              :defer true}]
    [:script
     (hiccup/raw "
           function renderMath() {
             if (window.renderMathInElement) {
               renderMathInElement(document.body, {
                 delimiters: [
                   {left: '$$', right: '$$', display: true},
                   {left: '$', right: '$', display: false},
                   {left: '\\\\(', right: '\\\\)', display: false},
                   {left: '\\\\[', right: '\\\\]', display: true}
                 ],
                 throwOnError: false
               });
             }
           }
           document.addEventListener('DOMContentLoaded', renderMath);
           document.addEventListener('htmx:afterSettle', renderMath);
         ")]]])

(defn render
  "HTML response for a full page."
  [request title & body]
  (reitit-extras/render-html (apply page request title body)))

(defn not-found
  "404 page — also used for documents and admin pages the user may not
   see, so their existence is not revealed."
  [request]
  (-> (render request "找不到頁面"
              [:h1 {:class ["text-2xl" "font-semibold"]} "找不到頁面"])
      (response/status 404)))
