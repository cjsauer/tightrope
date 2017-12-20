(ns leiningen.new.tightrope
  (:require [leiningen.new.templates :refer [renderer name-to-path ->files multi-segment
                                             sanitize-ns project-name year date]]
            [leiningen.core.main :as main]))

(def render (renderer "tightrope"))

(defn tightrope
  [name]
  (let [render (renderer "tightrope")
        main-ns (multi-segment (sanitize-ns name))
        data {:raw-name name
              :name (project-name name)
              :namespace main-ns
              :nested-dirs (name-to-path main-ns)
              :year (year)
              :date (date)}]
    (main/info "Generating fresh 'lein new' tightrope project.")
    (->files data
             ["project.clj" (render "project.clj" data)]
             ["README.md" (render "README.md" data)]

             ;; Clojure files
             ["dev/user.clj" (render "user.clj" data)]
             ["src/clj/{{nested-dirs}}/core.clj" (render "core.clj" data)]
             ["src/clj/{{nested-dirs}}/styles.clj" (render "styles.clj" data)]
             ["src/clj/{{nested-dirs}}/config.clj" (render "config.clj" data)]
             ["src/clj/{{nested-dirs}}/rest_routes.clj" (render "rest_routes.clj" data)]
             ["src/clj/{{nested-dirs}}/http_server.clj" (render "http_server.clj" data)]

             ;; ClojureScript files
             ["src/cljs/{{nested-dirs}}/core.cljs" (render "core.cljs" data)]
             )))
