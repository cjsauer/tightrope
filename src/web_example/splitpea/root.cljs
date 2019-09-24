(ns splitpea.root
  (:require [rum.core :as rum]
            [tightrope.core :as rope]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Dashboard

(def *user-dashboard
  {:idents [:user/handle]
   :query  [:user/handle]})

(rum/defc user-dashboard
  < (rope/ds-mixin *user-dashboard)
  [{user ::rope/data}]
  (when user
    (let [greeting (str "Hello, " (:user/handle user))]
      [:div
       [:h1 {:style {:font-size "2em"}} greeting]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login

(rum/defc login
  []
  [:div
   [:p "You need to log in"]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root

(def *root
  {:init-tx [{:db/ident ::root}]
   :idents  [:db/ident]
   :query   [{:user/me (:query *user-dashboard)}]})

(rum/defc root
  < (rope/ds-mixin *root)
  [{::rope/keys [data] :as p}]
  (let [me (:user/me data)]
    [:div
     {:style {:height          "100%"
              :flex-direction  "column"
              :justify-content "space-between"}}
     (if-not me
       (login)
       (user-dashboard me))]))
