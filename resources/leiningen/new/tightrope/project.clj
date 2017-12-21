(defproject {{raw-name}} "0.1.0-SNAPSHOT"
  :description "FIXME: describe your project"
  :url "FIXME: add a URL"

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.9.946" :scope "provided"]
                 [org.clojure/tools.namespace "0.2.11" :scope "test"]
                 [lambdaisland/garden-watcher "0.3.2" :scope "test"]
                 [ring/ring-defaults "0.3.1"]
                 [environ "1.1.0"]
                 [mount "0.1.11"]
                 [com.stuartsierra/component "0.3.2"]
                 [http-kit "2.2.0"]
                 [compojure "1.6.0"]
                 [hiccup "1.0.5"]]

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-environ "1.1.0"]
            [lein-ancient "0.6.14"]]

  :min-lein-version "2.5.3"

  :source-paths ["src/clj" "src/cljs" "src/cljc"]

  :test-paths ["test/clj" "test/cljc"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                    "resources/public/vendor"
                                    "target"
                                    "test/js"]

  :uberjar-name "{{name}}.jar"

  :main {{namespace}}.core

  :repl-options {:init-ns user}

  :profiles {:dev
             {:dependencies [[figwheel "0.5.14"]
                             [figwheel-sidecar "0.5.14"]
                             [com.cemerick/piggieback "0.2.2"]
                             [org.clojure/tools.nrepl "0.2.13"]
                             [lein-doo "0.1.8"]]

              :plugins [[lein-figwheel "0.5.14"]
                        [lein-doo "0.1.8"]]

              :source-paths ["dev"]
              :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}

             :uberjar
             {:source-paths ^:replace ["src/clj" "src/cljc"]
              :prep-tasks ["compile"
                           ["cljsbuild" "once" "min"]
                           ["run" "-m" "garden-watcher.main" "{{namespace}}.styles"]]
              :hooks []
              :omit-source true
              :aot :all}}

  :cljsbuild {:builds
              [{:id "app"
                :source-paths ["src/cljs" "src/cljc" "dev"]

                :figwheel {}

                :compiler {:main cljs.user
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/{{name}}.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true}}

               {:id "test"
                :source-paths ["src/cljs" "test/cljs" "src/cljc" "test/cljc"]
                :compiler {:output-to "resources/public/js/compiled/testable.js"
                           :main {{namespace}}.test-runner
                           :optimizations :none}}

               {:id "min"
                :source-paths ["src/cljs" "src/cljc"]
                :jar true
                :compiler {:main {{namespace}}.core
                           :output-to "resources/public/js/compiled/{{name}}.js"
                           :output-dir "target"
                           :source-map-timestamp true
                           :optimizations :advanced
                           :closure-defines {goog.DEBUG false}
                           :pretty-print false}}]}

  :doo {:build "test"}

  ;; When running figwheel from nREPL, figwheel will read this configuration
  ;; stanza, but it will read it without passing through leiningen's profile
  ;; merging. So don't put a :figwheel section under the :dev profile, it will
  ;; not be picked up, instead configure figwheel here on the top level.

  :figwheel {;; :http-server-root "public"       ;; serve static assets from resources/public/
             ;; :server-port 3449                ;; default
             ;; :server-ip "127.0.0.1"           ;; default
             :css-dirs ["resources/public/css"]  ;; watch and update CSS

             ;; Start an nREPL server into the running figwheel process. We
             ;; don't do this, instead we do the opposite, running figwheel from
             ;; an nREPL process, see
             ;; https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl
             ;; :nrepl-port 7888

             ;; To be able to open files in your editor from the heads up display
             ;; you will need to put a script on your path.
             ;; that script will have to take a file path and a line number
             ;; ie. in  ~/bin/myfile-opener
             ;; #! /bin/sh
             ;; emacsclient -n +$2 $1
             ;;
             ;; :open-file-command "myfile-opener"

             :server-logfile "log/figwheel.log"}

  ;; end defproject
  )

