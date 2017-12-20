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
             ["src/clj/{{nested-dirs}}/core.clj" (render "core.clj" data)])))
