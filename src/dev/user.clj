(ns user
  (:require [tightrope.dev :as rope-dev]))

(defn deploy
  []
  (rope-dev/ion-release {:creds-profile "sandbox"
                         :region "us-east-1"
                         :group "splitpea-dev-compute"}))
