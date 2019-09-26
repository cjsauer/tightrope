(ns splitpea.root
  (:require [rum.core :as rum]
            [tightrope.client :as rope]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Dashboard

;; Example of pure component
(rum/defc friendly-greeting
  < rum/static
  [{:keys [greeting]}]
  [:div
   [:h1 {:style {:font-size "2em"}} greeting]])

(def *user-dashboard
  {:idents [:user/handle]
   :query  [:user/handle :user/greeting]
   })

(rum/defc user-dashboard
  < (rope/ds-mixin *user-dashboard)
  [{user ::rope/data}]
  [:div
   [:pre (str "USER_DASH" user)]
   (friendly-greeting {:greeting (:user/greeting user)})])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authentication

(def *login-form
  {:mount-tx [{:db/ident :me
               :login/handle ""}]
   :lookup   [:db/ident :me]
   :query    [:user/me :login/handle]
   })

(rum/defc login-form
  < (rope/ds-mixin *login-form)
  [{::rope/keys [data upsert! mutate!]}]
  (let [{:login/keys [handle]} data
        login!                 #(mutate! 'splitpea.server.resolvers/login!
                                         (select-keys data [:login/handle]))]
    [:div
     [:input {:type        "text"
              :placeholder "enter a username"
              :value       (or handle "")
              :on-change   #(upsert!
                             {:login/handle (-> % .-target .-value)})}]
     [:button {:on-click login!} "Login!"]
     [:pre (str "LOGIN " data)]]))

(def *authn
  {:mount-tx [{:db/ident :me}]
   :lookup   [:db/ident :me]
   :query    [{:user/me (:query *user-dashboard)} :ui/freshening?]
   :freshen? true
   })

(rum/defc authn
  < (rope/ds-mixin *authn)
  [{::rope/keys [data]}]
  (let [{:user/keys [me]
         :ui/keys   [freshening?]} data]
    (when-not freshening?
      [:div
       [:pre (str "AUTHN " data)]
       (if me
         (user-dashboard me)
         (login-form))])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root

(rum/defc root
  []
  [:div
   (authn)])
