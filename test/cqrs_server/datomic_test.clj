(ns cqrs-server.datomic-test
  (:require
   [datomic.api :as d]
   [datomic-schema.schema :as ds :refer [schema fields part]]
   [schema.core :as s]
   [cqrs-server.async :as async]
   [cqrs-server.cqrs :as cqrs]
   [cqrs-server.datomic :as datomic]
   
   [onyx.api]
   [onyx.plugin.core-async]
   
   [clojure.core.async :as a]
   [clojure.test :refer :all]
   [taoensso.timbre :as log]))

;; First, lets define the most basic module
(def db-schema
  [(schema
       base
       (fields
        [uuid :uuid :unique-identity]))
   (schema
    user
    (fields
     [name :string :indexed :unique-identity]
     [age :long]))])

(cqrs/install-commands
  {:user/register {:name s/Str :age s/Int}})

;; :user/create
(defmethod cqrs/process-command :user/register [{:keys [data] :as c}]
  (if (first (d/q '[:find ?e :in $ ?name :where [?e :user/name ?name]] (datomic/command-db c) (:name data)))
    (cqrs/events c 0 [[:user/register-failed data]])
    (cqrs/events c 1 [[:user/registered data]])))

(defmethod cqrs/aggregate-event :user/registered [{:keys [data] :as e}]
  [{:db/id (d/tempid :db.part/user)
    :user/name (:name data)
    :user/age (:age data)}])


(def env-config
  {:zookeeper/address "127.0.0.1:2181"
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def peer-config
  {:zookeeper/address "127.0.0.1:2181"   
   :onyx.peer/inbox-capacity 100
   :onyx.peer/outbox-capacity 100
   :onyx.messaging/impl :http-kit-websockets
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def config
  {:command-stream (atom nil)
   :event-stream (atom nil)
   :feedback-stream (atom nil)
   :channels [:command-stream :event-stream :feedback-stream]
   :datomic-uri "datomic:mem://cqrs"})

(def catalog-map
  {:command/in-queue (async/stream :input (:command-stream config))
   :command/process (datomic/command-processor (:datomic-uri config))
   :event/out-queue (async/stream :output (:event-stream config))
   :event/in-queue (async/stream :input (:event-stream config))
   :event/store (dynamo/catalog local-cred)
   :event/aggregator (datomic/aggregate-stream (:datomic-uri config))
   :command/feedback (async/stream :output (:feedback-stream config))})

(defn setup-env [db-schema]
  (d/create-database (:datomic-uri config))
  (d/transact
   (d/connect (:datomic-uri config))
   (ds/generate-schema (concat datomic/db-schema db-schema)))
  (doseq [c (:channels config)]
    (reset! (get config c) (a/chan 10)))
  (let [setup (cqrs/setup (java.util.UUID/randomUUID) catalog-map)]
    {:onyx (cqrs/start-onyx setup env-config peer-config)}))

(defn stop-env [env]
  ((-> env :onyx :shutdown))
  (doseq [c (:channels config)]
    (swap! (get config c) (fn [chan] (a/close! chan) nil)))
  (d/delete-database (:datomic-uri config))
  true)

(defn command [type data]
  (cqrs/command (d/basis-t (d/db (d/connect (:datomic-uri config)))) type data))

(defn send-command [type data]
  (a/>!! @(:command-stream config) (command type data)))

(deftest run-test []
  (let [env (setup-env db-schema)
        event (delay (first (a/alts!! [@(:event-store-stream config) (a/timeout 1000)])))
        feedback (delay (first (a/alts!! [@(:feedback-stream config) (a/timeout 2000)])))]
    (try
      (send-command :user/register {:name "Bob" :age 33})
      (assert (= (:tp @event) :user/registered))
      (assert @feedback)
      (assert (= #{["Bob" 33]} (d/q '[:find ?n ?a :where [?e :user/name ?n] [?e :user/age ?a]] (d/as-of (d/db (d/connect (:datomic-uri config))) (:ta @feedback)))))
      (finally
        (stop-env env)))))
