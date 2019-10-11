(ns splitpea.server.ion
  (:require [datomic.ion.lambda.api-gateway :as apigw]
            [splitpea.server :as server]))

(def ionized-handler
  (apigw/ionize server/handler))
