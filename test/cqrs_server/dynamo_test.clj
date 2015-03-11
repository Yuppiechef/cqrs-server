(ns cqrs-server.dynamo-test
  (:require
   [datomic.api :as d]
   [datomic-schema.schema :as ds :refer [schema fields part]]
   [schema.core :as s]
   [cqrs-server.module :as module]
   [cqrs-server.cqrs :as cqrs]
   [onyx.peer.task-lifecycle-extensions :as l-ext]
   
   [onyx.api]
   
   [clojure.core.async :as a]
   [clojure.test :refer :all]
   [taoensso.timbre :as log]
   [taoensso.faraday :as far]
   [taoensso.nippy :as nippy]
   
   [cqrs-server.async :as async]
   [cqrs-server.dynamo :as dynamo]))


(def hornet {:host "localhost" :port 5465})

(def env-config
  {:hornetq/mode :standalone
   :hornetq/server? true
   :hornetq.server/type :embedded
   :hornetq.embedded/config ["hornetq/non-clustered-1.xml"]
   :hornetq.standalone/host (:host hornet)
   :hornetq.standalone/port (:port hornet)
   :zookeeper/address "127.0.0.1:2181"
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def peer-config
  {:hornetq/mode :standalone
   :hornetq.standalone/host (:host hornet)
   :hornetq.standalone/port (:port hornet)
   :zookeeper/address "127.0.0.1:2181"
   :onyx.peer/inbox-capacity 100
   :onyx.peer/outbox-capacity 100
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def config
  {:datomic-uri "datomic:mem://cqrsasync"
   :command-stream (atom nil)
   :event-stream (atom nil)
   :aggregate-out-stream (atom nil)
   :channels [:command-stream :event-stream :aggregate-out-stream]})



(def catalog
  (cqrs/catalog
   {:command-queue (async/chan-stream :input)
    :out-event-queue (async/chan-stream :output)
    :in-event-queue (async/chan-stream :input)
    :event-store (assoc (dynamo/catalog dynamo/local-cred) :onyx/batch-size 1)
    :aggregate-out (async/chan-stream :output)}))

(async/chan-register :command/in-queue :input (:command-stream config))
(async/chan-register :event/out-queue :output (:event-stream config))
(async/chan-register :event/in-queue :input (:event-stream config))
(async/chan-register :event/aggregate-out :output (:aggregate-out-stream config))

(defn setup-env [db-schema]
  (dynamo/table-setup dynamo/local-cred)

  (let [dturi (:datomic-uri config)]
    (d/create-database dturi)
    (d/transact (d/connect dturi) (ds/generate-schema d/tempid db-schema))
    (reset! cqrs/datomic-uri dturi))
  
  (doseq [c (:channels config)]
    (reset! (get config c) (a/chan 10)))
  (let [onyxid (java.util.UUID/randomUUID)
        penv (assoc env-config :onyx/id onyxid)
        pconfig (assoc peer-config :onyx/id onyxid)
        env (onyx.api/start-env penv)
        peers (onyx.api/start-peers! 10 pconfig)]
    {:env env
     :pconfig pconfig
     :peers peers
     :job (onyx.api/submit-job
           pconfig
           {:catalog catalog :workflow cqrs/command-workflow :task-scheduler :onyx.task-scheduler/round-robin})}))

(defn stop-env [env]
  (d/delete-database (:datomic-uri config))
  
  (try
    (far/delete-table dynamo/local-cred (:tablename dynamo/local-cred))
    (catch Exception e nil))
  
  (onyx.api/kill-job (:pconfig env) (:job env))
  (doseq [p (:peers env)] (onyx.api/shutdown-peer p))
  (onyx.api/shutdown-env (:env env))
  (doseq [c (:channels config)]
    (a/close! @(get config c))
    (reset! (get config c) nil))
  true)

(defn command [type data]
  (cqrs/command (d/basis-t (d/db (d/connect (:datomic-uri config)))) type data))

(defn send-command [type data]
  (a/>!! @(:command-stream config) (command type data)))

(def db-schema
  (concat
   cqrs/db-schema
   [(schema
     base
     (fields
      [uuid :uuid :unique-identity]))]
   module/db-schema))

(deftest run-test []
  (let [env (setup-env db-schema)
        aggregate (delay (first (a/alts!! [@(:aggregate-out-stream config) (a/timeout 5000)])))]
    (try
      (send-command :user/register {:name "Bob" :age 33})
      (assert @aggregate)
      (assert (= #{["Bob" 33]} (d/q '[:find ?n ?a :where [?e :user/name ?n] [?e :user/age ?a]] (d/as-of (d/db (d/connect (:datomic-uri config))) (:t @aggregate)))))
      (assert (= 1 (count (far/scan dynamo/local-cred :eventstore))))
      (assert (= {:age 33 :name "Bob"} (nippy/thaw (:data (first (far/scan dynamo/local-cred :eventstore))))))
      (finally
        (stop-env env)))))
