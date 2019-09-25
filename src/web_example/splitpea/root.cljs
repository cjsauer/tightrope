(ns splitpea.root
  (:require [rum.core :as rum]
            [tightrope.rum :as rope]
            [splitpea.resolvers :as resolvers]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Dashboard

(def *user-dashboard
  {:idents   [:user/handle]
   ;; TODO: :server/time is transacted onto user entity on freshen...
   ;; Instead of freshen? being a boolean, maybe it could be a keyset
   :query    [:user/greeting :server/time :ui/freshening?]
   })

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
  [{:keys       [login!]
    ::rope/keys [data upsert!]}]
  [:div
   [:input {:type        "text"
            :placeholder "enter a username"
            :value       (or (:login/handle data) "")
            :on-change   #(upsert! {:login/handle (-> % .-target .-value)})}]
   [:button {:on-click (partial login! data)} "Login!"]
   [:pre (str data)]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Root

(def *root
  {:init-tx {:db/ident   :root
             :user/login (:init-tx *login)}
   :lookup  [:db/ident :root]
   :query   [:user/me :user/login :ui/mutating?]})

(rum/defc root
  < (rope/ds-mixin *root)
  [{::rope/keys [data mutate!]}]
  (let [me        (:user/me data)
        login!    #(mutate! `resolvers/login! % [:user/me])
        mutating? (:ui/mutating? data)]
    [:div
     {:style {:height          "100%"
              :flex-direction  "column"
              :justify-content "space-between"}}
     [:pre (str data)]
     (cond
       mutating?  [:p "Logging in..."]
       (nil? me)  (login (merge {:login! login!}
                                (:user/login data)))
       (some? me) (user-dashboard me))]))
