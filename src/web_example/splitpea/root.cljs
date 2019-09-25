(ns splitpea.root
  (:require [rum.core :as rum]
            [tightrope.rum :as rope]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Dashboard

(def *user-dashboard
  {:idents   [:user/handle]
   :query    [:user/greeting :server/time :ui/freshening?]
   :freshen? true})

(rum/defc user-dashboard
  < (rope/ds-mixin *user-dashboard)
  [{user     ::rope/data
    freshen! ::rope/freshen!}]
  (if (:ui/freshening? user)
    [:div [:p "Loading..."]]
    [:div
     [:pre (str user)]
     [:h1 {:style {:font-size "2em"}} (:user/greeting user)]
     (when-let [server-time (:server/time user)]
       [:p server-time])
     [:button {:on-click freshen!} "Freshen!"]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login

(def *login
  {:init-tx {:login/handle ""}
   :idents  [:db/id]
   :query   [:login/handle]})

(rum/defc login
  < (rope/ds-mixin *login)
  [{::rope/keys [data upsert!]}]
  [:div
   [:input {:type "text"
            :placeholder "enter a username"
            :value (or (:login/handle data) "")
            :on-change #(upsert! {:login/handle (-> % .-target .-value)})}]
   ;; [:button {:on-click }]
   [:pre (str data)]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root

(def *root
  {:init-tx {:db/ident   ::root
             :user/login (:init-tx *login)}
   :lookup  [:db/ident ::root]
   :query   [:user/me :user/login]})

(rum/defc root
  < (rope/ds-mixin *root)
  [{::rope/keys [data]}]
  (let [me (:user/me data)]
    [:div
     {:style {:height          "100%"
              :flex-direction  "column"
              :justify-content "space-between"}}
     [:pre (str data)]
     (if-not me
       (login (:user/login data))
       (user-dashboard me))]))
