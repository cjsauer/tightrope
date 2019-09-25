(ns splitpea.server.resolvers
  "Server-only resolvers"
  (:require [com.wsscode.pathom.connect :as pc]))

(pc/defresolver server-time
  [_ _]
  {::pc/output #{:server/time}}
  {:server/time (str (java.util.Date.))})

(pc/defresolver me
  [_ _]
  {::pc/output #{:user/me}}
  {:user/me {:user/handle "Calvin"}})

(pc/defmutation login!
  [_ {:login/keys [handle]}]
  {::pc/input #{:login/handle}
   ::pc/output #{:user/me}}
  (println "Loggin in user: " handle)
  {:user/me {:user/handle handle}})

(def all [server-time
          me
          login!])
