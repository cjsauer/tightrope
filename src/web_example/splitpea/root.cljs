(ns splitpea.root
  (:require [rum.core :as rum]
            [tightrope.client :as rope]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login

(def *login-form
  {:mount-tx [{:db/ident :me
               :login/handle ""}]
   :lookup   [:db/ident :me]
   :query    [:user/me :login/handle]
   })

(rum/defc login-form
  < (rope/ds-mixin *login-form)
  [{::rope/keys [data upsert! mutate!]}]
  (let [login! #(mutate! 'splitpea.server.resolvers/login! data)]
    [:div
     [:input {:type        "text"
              :placeholder "enter a username"
              :value       (or (:login/handle data) "")
              :on-change   #(upsert! {:login/handle (-> % .-target .-value)})}]
     [:button {:on-click login!} "Login!"]
     [:pre (str data)]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Dashboard

(def *user-card
  {:idents [:user/handle]
   :query  [:user/handle :user/greeting]})

(rum/defc user-card
  < (rope/ds-mixin *user-card)
  [{::rope/keys [data]}]
  [:div
   [:h1 (:user/greeting data)]
   [:pre (str data)]])

(def *user-dashboard
  {:mount-tx [{:db/ident :me}]
   :lookup   [:db/ident :me]
   :query    [:user/me :ui/freshening?]
   })

(rum/defc user-dashboard
  < (rope/ds-mixin *user-dashboard)
  [{::rope/keys [data]}]
  (when-not (:ui/freshening? data)
    (if-let [user (:user/me data)]
      (user-card user)
      (login-form))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root

(rum/defc root
  []
  [:div
   (user-dashboard)])
