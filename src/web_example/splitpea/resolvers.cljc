(ns splitpea.resolvers
  (:require [com.wsscode.pathom.connect :as pc]))

(pc/defresolver greeting-resolver
  [_ {:user/keys [handle]}]
  {::pc/input  #{:user/handle}
   ::pc/output #{:user/greeting}}
  {:user/greeting (str "Hello, " handle)})

(def main [greeting-resolver])
