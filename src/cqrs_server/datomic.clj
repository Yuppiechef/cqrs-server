(ns cqrs-server.datomic
  (:require
   [datomic.api :as d]
   [datomic-schema.schema :refer [schema fields defdbfn]]))

(defdbfn idempotent-tx [db eid tx]
  (if-not (datomic.api/entity db [:event/uuid eid])
    (concat [[:db/add (datomic.api/tempid :db.part/tx) :event/uuid eid]] tx) []))

(defdbfn add [db entid attr value]
  (let [ent (datomic.api/entity db entid)]
    (if ent
      [{:db/id entid attr (+ (or (get ent attr) 0) value)}] [])))

(def db-schema
  [(:tx (meta idempotent-tx))
   (:tx (meta add))
   (schema
    event
    (fields
     [uuid :uuid :unique-identity]))])

(defn command-db [{:keys [t datomic-uri]}]
  (let [db (d/db (d/connect datomic-uri))]
    (if t (d/as-of db t) db)))

(defn writer [datomic-uri e tx]
  (let [t @(d/transact (d/connect datomic-uri)
                       [[:idempotent-tx (java.util.UUID/fromString (str (:id e))) tx]])]
    [(assoc e :ta (d/basis-t (:db-after t)))]))

(defn lifecycle [task-map]
  {:onyx.core/params [writer (:datomic/uri task-map)]})

(defn aggregate-stream [datomic-uri]
  {:entry
   {:datomic/uri datomic-uri}
   :lifecycle lifecycle})

(defn command-processor [datomic-uri]
  {:entry
   {:onyx/params [:datomic/uri-key :datomic/uri]
    :datomic/uri-key :datomic-uri
    :datomic/uri datomic-uri}})
