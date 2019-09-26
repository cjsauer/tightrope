(ns splitpea.client.resolvers
  "Client-only resolvers"
  (:require [com.wsscode.pathom.connect :as pc]))

(pc/defresolver user-token
  [_ _]
  {::pc/output #{:user/token}}
  {:user/token "calvin"})

(def all [user-token])
