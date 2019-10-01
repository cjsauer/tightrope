(ns splitpea.model)

(def schema {:user/me     {:db/valueType :db.type/ref}
             :user/handle {:db/unique :db.unique/identity}
             :login/form  {:db/valueType :db.type/ref}})
