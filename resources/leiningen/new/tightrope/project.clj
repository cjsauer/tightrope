(defproject {{raw-name}} "0.1.0-SNAPSHOT"
  :description "FIXME: describe your project"
  :url "FIXME: add a URL"

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.9.946" :scope "provided"]]

  :min-lein-version "2.5.3"

  :source-paths ["src/clj" "src/cljs" "src/cljc"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                    "resources/public/vendor"
                                    "target"
                                    "test/js"]

  :uberjar-name "{{name}}.jar"

  :main {{namespace}}.core

  :repl-options {:init-ns user} 
 )

