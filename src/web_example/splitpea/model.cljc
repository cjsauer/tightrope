(ns splitpea.model)

(def schema {:db/ident    {:db/unique :db.unique/identity}
             :user/me     {:db/valueType :db.type/ref}
             :user/handle {:db/unique :db.unique/identity}})
