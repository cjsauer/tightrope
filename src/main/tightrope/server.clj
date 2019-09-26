(ns tightrope.server
  (:require [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [clojure.core.async :as a :refer [<!!]]
            [compojure.core :as compj :refer [POST]]
            [clojure.edn :as edn]))

;; TODO should use transit+json encoding here for speed

(defn- handler
  [ctx req]
  (let [{:keys [parser]} ctx
        query            (-> req :body slurp edn/read-string)
        parse-result     (<!! (parser {:request req} query))]
    {:status  200
     :headers {"Content-Type" "application/edn"}
     :body    (str parse-result)}))

(defn- default-parser
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
                 p/elide-special-outputs-plugin
                 p/error-handler-plugin
                 p/trace-plugin]}))

(defn tightrope-handler
  [{:keys [remote parser-opts] :as handler-opts}]
  (let [parser (or (:parser handler-opts)
                   (default-parser parser-opts))]
    (compj/routes
     (POST (:uri remote) [] (partial handler {:parser parser})))))
