(ns splitpea.server.resolvers
  "Server-only resolvers"
  (:require [com.wsscode.pathom.connect :as pc]))

(def users (atom {"calvin" {:user/handle "calvin"}}))

(pc/defresolver server-time
  [_ _]
  {::pc/output #{:server/time}}
  {:server/time (str (java.util.Date.))})

(pc/defresolver me
  [{:keys [request]} _]
  {::pc/output #{:user/me}}
  (when-let [authz (-> request :headers (get "authorization"))]
    ;; Placeholder for more sophisticated token verification
    {:user/me (get @users authz)}))

(pc/defmutation login!
  [_ {:login/keys [handle]}]
  {::pc/input #{:login/handle}
   ::pc/output #{:user/me}}
  (when-let [user (get @users handle)]
    (println "Loggin in user: " handle)
    {:user/me user}))

(def all [server-time
          me
          login!])
