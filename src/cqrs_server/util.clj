(ns cqrs-server.util
  (:require
   [datomic.api]
   [datomic.function :as df]))

(defmacro defdbfn
  "Define a datomic database function. All calls to datomic api's should be namespaced with datomic.api/ and you cannot use your own namespaces (since the function runs inside datomic)

  This defines a locally namespaced function as well - which is useful for testing.

  Your first parameter needs to always be 'db'.

  You'll need to commit the actual function's meta into your datomic instance by calling (d/transact (meta myfn))"
  [name params & code]
  `(def ~name
     (with-meta
       (fn ~name [~@params]
         ~@code)
       {:tx
        {:db/id (datomic.api/tempid :db.part/user)
         :db/ident ~(keyword name)
         :db/fn (df/construct
                 {:lang "clojure"
                  :params '~params
                  :code '~@code})}})))
