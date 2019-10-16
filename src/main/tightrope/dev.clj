(ns tightrope.dev
  (:require [datomic.ion.dev :as ion-dev]))

(defn ion-release
  "Push and deploy ion application. args is a map of:
    - `:region` aws region
    - `:creds-profile` aws creds profile
    - `:group` name of datomic compute group
    - `:uname` (optional) unreproducible build name"
  [args]
  (try
    (let [push-data   (ion-dev/push args)
          deploy-args (merge (select-keys args [:creds-profile :region :uname])
                             (select-keys push-data [:rev])
                             {:group (:group args)})]
      (let [deploy-data        (ion-dev/deploy deploy-args)
            deploy-status-args (merge (select-keys args [:creds-profile :region])
                                      (select-keys deploy-data [:execution-arn]))]
        (loop []
          (let [status-data (ion-dev/deploy-status deploy-status-args)]
            (if (= "RUNNING" (:code-deploy-status status-data))
              (do (Thread/sleep 5000) (recur))
              status-data)))))
    (catch Exception e
      {:deploy-status "ERROR"
       :message       (.getMessage e)})))
