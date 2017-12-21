(ns leiningen.new.tightrope
  (:require [leiningen.new.templates :refer [renderer name-to-path ->files multi-segment
                                             sanitize-ns project-name year date sanitize]]
            [leiningen.core.main :as main]))

(defn tightrope
  [name]
  (let [render (renderer "tightrope")
        main-ns (multi-segment (sanitize-ns name))
        data {:raw-name name
              :name (project-name name)
              :namespace main-ns
              :namespace-path (sanitize main-ns)
              :nested-dirs (name-to-path main-ns)
              :year (year)
              :date (date)}]
    (main/info "Generating fresh 'lein new' tightrope project.")
    (->files data
             ["project.clj" (render "project.clj" data)]
             ["README.md" (render "README.md" data)]

             ;; Clojure files
             ["dev/clj/user.clj" (render "dev/clj/user.clj" data)]
             ["src/clj/{{nested-dirs}}/core.clj" (render "/src/clj/tightrope/core.clj" data)]
             ["src/clj/{{nested-dirs}}/styles.clj" (render "src/clj/tightrope/styles.clj" data)]
             ["src/clj/{{nested-dirs}}/config.clj" (render "src/clj/tightrope/config.clj" data)]
             ["src/clj/{{nested-dirs}}/rest_routes.clj" (render "src/clj/tightrope/rest_routes.clj" data)]
             ["src/clj/{{nested-dirs}}/http_server.clj" (render "src/clj/tightrope/http_server.clj" data)]

             ;; ClojureScript files
             ["dev/cljs/user.cljs" (render "dev/cljs/user.cljs" data)]
             ["src/cljs/{{nested-dirs}}/core.cljs" (render "src/cljs/tightrope/core.cljs" data)]
             )))
