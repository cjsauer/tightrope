(ns splitpea.server
  (:require [tightrope.server :as rope]
            [splitpea.resolvers :as shared-resolvers]
            [splitpea.server.resolvers :as server-resolvers]
            [com.wsscode.pathom.connect :as pc]))

(def handler
  (rope/tightrope-handler
   {:parser-opts {:resolvers (concat shared-resolvers/all
                                     server-resolvers/all)}
    :path "/api"
    }))


(comment

  (require '[clojure.java.io :as io])

  (handler
   {:request-method :post
    :uri "/api"
    :body (io/input-stream (.getBytes (str [{`(server-resolvers/login! {:login/handle "calvin"})
                                             [:user/me]}])))})

  )
