(ns splitpea.root
  (:require [rum.core :as rum]
            [tightrope.client :as rope]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login

(def *login
  {:init-tx       {::rope/id (rope/ropeid)
                   :login/handle ""}
   :idents        [::rope/id]
   :query         [::rope/id :login/handle]
   :auto-retract? true})

(rum/defc login
  < (rope/ds-mixin *login)
    rum/static
  [{:keys       [login-lookup login-query]
    ::rope/keys [data upsert! mutate!]}]
  (let [login! #(mutate! login-lookup 'splitpea.server.resolvers/login! data login-query)]
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
    rum/static
  [{::rope/keys [data]}]
  [:div
   [:h1 (:user/greeting data)]
   [:pre (str data)]])

(def *user-dashboard
  {:mount-tx [{:db/ident   :me
               :form/login (:init-tx *login)}]
   :lookup   [:db/ident :me]
   :query    [{:user/me (:query *user-card)}
              {:form/login (:query *login)}
              :ui/freshening?]
   ;; :freshen? true
   })

(rum/defc user-dashboard
  < (rope/ds-mixin *user-dashboard)
  rum/static
  [{::rope/keys [data]}]
  (when-not (:ui/freshening? data)
    (if-let [user (:user/me data)]
      (user-card user)
      (login (merge
              (:form/login data)
              {:login-lookup (:lookup *user-dashboard)
               :login-query  (:query *user-dashboard)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root

(rum/defc root
  < rum/static
  []
  [:div
   (user-dashboard)])
