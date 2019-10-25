(ns tightrope.server.handler
  (:require [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [clojure.core.async :as a :refer [<!!]]
            [compojure.core :as compj :refer [POST]]
            [clojure.edn :as edn]
            [muuntaja.middleware :as mja]))

(defn- handler
  [ctx req]
  (let [{:keys [parser]} ctx
        query            (:body-params req)
        parse-result     (<!! (parser {:request req} query))]
    {:status  200
     :body    parse-result}))

(defn- make-parser
  [{:keys [env resolvers plugins]}]
  (p/parallel-parser
   {::p/env     (merge
                 {::p/reader               [p/map-reader
                                            pc/parallel-reader
                                            pc/open-ident-reader
                                            p/env-placeholder-reader]
                  ::p/placeholder-prefixes #{">"}}
                 env)
    ::p/mutate  pc/mutate-async
    ::p/plugins (concat plugins
                        [(pc/connect-plugin {::pc/register (or resolvers [])})
                         p/elide-special-outputs-plugin
                         p/error-handler-plugin
                         p/trace-plugin])}))

(defn http-handler
  [{:keys [path parser-opts] :as config}]
  (let [parser (or (:parser config)
                   (make-parser parser-opts))
        ctx    {:parser parser}
        routes (compj/routes
                (POST path [] (partial handler ctx)))]
    (-> routes
        mja/wrap-format)))
