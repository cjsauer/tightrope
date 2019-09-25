(ns splitpea.resolvers
  (:require [com.wsscode.pathom.connect :as pc]))

(pc/defresolver greeting-resolver
  [_ {:user/keys [handle]}]
  {::pc/input  #{:user/handle}
   ::pc/output #{:user/greeting}}
  {:user/greeting (str "Hello, " handle)})

(pc/defmutation login!
  [_ {:login/keys [handle]}]
  {::pc/input #{:login/handle}
   ::pc/output #{:user/me}}
  #?(:clj (println "SERVER LOGIN")
     :cljs (println "CLIENT LOGIN"))
  {:user/me {:user/handle handle}})

(def main [greeting-resolver
           login!])
