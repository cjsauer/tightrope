(ns splitpea.server
  (:require [tightrope.server :as rope]
            [splitpea.resolvers :as shared-resolvers]
            [splitpea.server.resolvers :as server-resolvers]
            [splitpea.server.db :as db]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.connect.datomic :as pcd]
            [com.wsscode.pathom.connect.datomic.client :refer [client-config]]
            ))

(defn custom-parser
  [{:keys [env resolvers]}]
  (p/parallel-parser
   {::p/env     (merge
                 {::p/reader               [p/map-reader
                                            pc/parallel-reader
                                            pc/open-ident-reader
                                            p/env-placeholder-reader]
                  ::p/placeholder-prefixes #{">"}}
                 env)
    ::p/mutate  pc/mutate-async
    ::p/plugins [(pc/connect-plugin {::pc/register (or resolvers [])})
                 (pcd/datomic-connect-plugin (assoc client-config ::pcd/conn (:conn env)))
                 p/elide-special-outputs-plugin
                 p/error-handler-plugin
                 p/trace-plugin]}))

(defn handler
  [req]
  (let [all-resolvers (concat shared-resolvers/all
                              server-resolvers/all)
        rope-config   {:path "/api"
                       :parser (custom-parser {:resolvers all-resolvers
                                               :env {:conn (db/get-conn)}})}
        rope-handler  (rope/tightrope-handler rope-config)]
    (rope-handler req)))


(comment

  (require '[clojure.java.io :as io]
           '[clojure.core.async :as a])

  (let [parser (custom-parser {:resolvers (concat shared-resolvers/all server-resolvers/all)
                               :env {:conn (db/get-conn)}})]
    (a/<!! (parser {} [{[:team/slug "red-team"] [{:team/members [:user/email]}]}])))

  (-> (handler
       {:request-method :post
        :uri "/api"
        :headers {"accept" "application/edn"
                  "content-type" "application/edn"}
        :body (io/input-stream (.getBytes (str [{[:team/slug "red-team"] [{:team/members [:user/email]}]}])))})
      :body
      slurp
      )

  )
