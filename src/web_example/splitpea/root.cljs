(ns splitpea.root
  (:require [rum.core :as rum]
            [tightrope.core :as rope]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Dashboard

(def *user-dashboard
  {:init-tx {:user/handle "Calvin"}
   :idents  [:user/handle]
   :query   [:user/greeting]})

(rum/defc user-dashboard
  < (rope/ds-mixin *user-dashboard)
  [{user ::rope/data}]
  [:div
   [:pre (str user)]
   [:h1 {:style {:font-size "2em"}} (:user/greeting user)]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login

(rum/defc login
  []
  [:div
   [:p "You need to log in"]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root

(def *root
  {:init-tx {:db/ident ::root
             :user/me  (:init-tx *user-dashboard)}
   :lookup  [:db/ident ::root]
   :query   [:user/me]})

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
