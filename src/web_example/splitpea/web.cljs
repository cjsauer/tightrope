(ns splitpea.web
  "Entry point of the splitpea web application"
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require ["react" :as r]
            [rum.core :as rum]
            [datascript.core :as ds]
            [cljs.core.async :as a :refer [<!]]
            [cljs-http.client :as http]
            [tightrope.core :as rope]
            [splitpea.model :as model]))

(defonce app-ctx (rope/make-framework-context
                  {:schema model/schema}))

(def initial-tx
  [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Message entry

(defn message-button
  [{:keys [label]}]
  [:button {:style {:width "15%"
                    :height "40px"}}
   label])

(defn message-input
  [{:keys [placeholder value on-change]}]
  [:input {:type "text"
           :value (or value "")
           :on-change #(on-change (-> % .-target .-value))
           :style {:width "80%"
                   :height "40px"
                   :font-size "1em"}
           :placeholder placeholder}])

(defn message-entry
  [{:keys [placeholder button-label message on-change]}]
  [:div
   {:style {:height "10%"
            :margin "10px 0px"}}
   (message-input {:placeholder placeholder
                   :value message
                   :on-change on-change})
   (message-button {:label button-label})])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; New topic
;; (Smart/Container component)

(def new-topic-init-tx
  {:new-topic/message ""})

(defn topic-preview
  [{:keys [message]}]
  [:p message])

(rum/defc new-topic
  < (rope/ds-mixin)
  [{:keys       [lookup]
    ::rope/keys [data conn]
    :as         props}]
  (let [{:new-topic/keys [message]} data
        on-change                   #(rope/upsert! conn lookup {:new-topic/message %})
        entry-props                 (merge (select-keys props [:placeholder :button-label])
                                           {:message   message
                                            :on-change on-change})]
    [:div
     (message-entry entry-props)
     (topic-preview {:message message})]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Topics

(defn topic-seed
  [{:keys [message]}]
  [:div
   {:style {:padding "20px 10px"
            :border-bottom "2px solid #666"}}
   [:p
    {:style {:font-size "1.2em"}}
    message]])

(defn topic-message
  [{:keys [message]}]
  [:div
   {:style {:font-size "1.2em"
            :border-bottom "1px dashed #ddd"}}
   [:p
    {:style {:font-size "1em"}}
    message]])

(defn topic-feed
  []
  [:div
   {:style {:display "flex"
            :flex-direction "column"
            ;; :height "100%"
            :border "1px solid #aaa"}}
   (topic-seed {:message "What do you guys think of Carrot?"})
   [:div
    {:style {:padding "10px"
             :overflow-y "scroll"
             :height "90%"
             }}
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    (topic-message {:message "Yo this is a much longer reply than what was there previously. With many sentences."})
    ]
   (message-entry {:placeholder "send a reply"
                   :button-label "Reply"
                   :message ""})
   ])

(defn feed-window
  []
  [:div
   {:style {:display "flex"
            :flex-direction "row"
            :height "90vh"
            }}
   (topic-feed)
   (topic-feed)
   (topic-feed)
   ])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root

(def root-init-tx
  [{:db/ident ::root
    :root/new-topic (conj new-topic-init-tx [:db/ident ::new-topic])}])

(rum/defc root
  < (rope/ds-mixin {:lookup [:db/ident ::root]})
  [{::rope/keys [data]}]
  ;; Wait...we now have a lazy entity here. Why don't we just pass that down to subcomponents?
  ;; Answer: YES! Do that! The ds-mixin is a tool for when that's not possible.
  ;; When is that not possible?
  ;;
  ;; If you took this to the extreme, every event handler would rise to the top of the program...
  ;;
  ;; The fact that we're using Datascript as the state/reactive mechanism is kind of an implementation
  ;; detail. This is why using Pathom on the client is so useful. There are more data sources than just
  ;; app state. Local storage, cookies, and even APIs.
  ;;
  ;; It's fine tho to use Datascript as the reactive UI piece. The paradigm of mounting entities to the
  ;; UI is still awesome. And using Datascript in client-side resolvers is really nice. It might even
  ;; be possible to write a Pathom plugin that integrates Datascript in an automated fashion. This would
  ;; preclude the need for devs to write resolvers for things in app state; you would only need to write
  ;; resolvers for "out of band" data.
  ;;
  ;; The primary difficulty in UI dev is that rarely, if ever, does the data that you need actually
  ;; come packaged in the same shape as your UI tree. So, you can either hand me tree-like data that
  ;; you've already packaged, OR you can hand me a database that can produce arbitrary tree-like data
  ;; on-demand from a lookup. You can control the lookup, or the database will provide one for you (:db/id).
  (let [new-topic-data (:root/new-topic data)]
    [:div
     {:style {:height "100%"
              :flex-direction "column"
              :justify-content "space-between"}}
     [:div
      {:style {:height "10vh"}}
      [:h1 {:style {:font-size "2em"}} "splitpea"]
      (new-topic {:lookup (:db/id new-topic-data)
                  :placeholder "start a new topic"
                  :button-label "Post"})]
     (feed-window)]))

(defn ^:dev/after-load mount
  []
  (rope/reset-registry! app-ctx)
  (rum/mount
   (rope/ctx-provider app-ctx (root))
   (.getElementById js/document "app")))

(defn start!
  []
  (ds/transact! (:conn app-ctx) root-init-tx)
  (mount))

