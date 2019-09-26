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
   :query  [:user/greeting]
   :freshen? true
   })

(rum/defc user-dashboard
  < (rope/ds-mixin *user-dashboard)
  [user]
  [:div
   [:pre (str user)]
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
  [{:login/keys [handle]
    ::rope/keys [upsert! mutate!] :as data}]
  (let [login! #(mutate! 'splitpea.server.resolvers/login!
                         (select-keys data [:login/handle]))]
    [:div
     [:input {:type        "text"
              :placeholder "enter a username"
              :value       (or handle "")
              :on-change   #(upsert! {:login/handle (-> % .-target .-value)})}]
     [:button {:on-click login!} "Login!"]
     [:pre (str data)]]))

(def *authn
  {:mount-tx [{:db/ident :me}]
   :lookup   [:db/ident :me]
   :query    [:user/me]
   })

(rum/defc authn
  < (rope/ds-mixin *authn)
  [{:user/keys [me]}]
  [:div
   #_[:pre (str data)]
   (if me
    (user-dashboard me)
    (login-form))])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root

(rum/defc root
  []
  [:div
   (authn)])
