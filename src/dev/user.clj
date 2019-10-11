(ns user
  (:require [tightrope.dev :as rope-dev]))

(defn deploy
  [& [args]]
  (rope-dev/ion-release (merge
                         {:creds-profile "sandbox"
                          :region "us-east-1"
                          :group "splitpea-dev-compute"}
                         args)))
