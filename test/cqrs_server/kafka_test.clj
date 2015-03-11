(ns cqrs-server.kafka-test
  (:require
   [datomic.api :as d]
   [datomic-schema.schema :as ds :refer [schema fields part]]
   
   [schema.core :as s]
   [cqrs-server.module :as module]
   [cqrs-server.async :as async]
   [cqrs-server.cqrs :as cqrs]
   [cqrs-server.kafka :as kafka]
   [onyx.peer.task-lifecycle-extensions :as l-ext]
   [clj-kafka.producer :as kp]
   [clj-kafka.consumer.zk :as zk]
   [clj-kafka.core :as k]
   [clojure.data.fressian :as fressian]
   
   [onyx.api]
   
   [clojure.core.async :as a]
   [clojure.test :refer :all]
   [taoensso.timbre :as log]))


(def env-config
  {:hornetq/mode :vm
   :hornetq/server? true
   :hornetq.server/type :vm
   :zookeeper/address "127.0.0.1:2181"
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def peer-config
  {:hornetq/mode :vm
   :zookeeper/address "127.0.0.1:2181"
   :onyx.peer/inbox-capacity 100
   :onyx.peer/outbox-capacity 100
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def config
  {:datomic-uri "datomic:mem://cqrsasync"
   :command-queue "command-queue"
   :kafka (kafka/config "127.0.0.1:9092" "127.0.0.1:2181")
   :event-queue "event-queue"
   :event-store-stream (atom nil)
   :aggregate-out-stream (atom nil)
   :channels [:event-store-stream :aggregate-out-stream]})

(def catalog
  (cqrs/catalog
   {:command-queue (kafka/catalog (:command-queue config) :input "cqrs" (:kafka config))
    :out-event-queue (kafka/catalog (:event-queue config) :output "cqrs" (:kafka config))
    :in-event-queue (kafka/catalog (:event-queue config) :input "cqrs" (:kafka config))
    :event-store (async/chan-stream :output)
    :aggregate-out (async/chan-stream :output)}))


(async/chan-register :event/store :output (:event-store-stream config))
(async/chan-register :event/aggregate-out :output (:aggregate-out-stream config))


(defn setup-env [db-schema]
  (doseq [c (:channels config)]
    (reset! (get config c) (a/chan 10)))
  (let [onyxid (java.util.UUID/randomUUID)
        penv (assoc env-config :onyx/id onyxid)
        pconfig (assoc peer-config :onyx/id onyxid)
        env (onyx.api/start-env penv)
        peers (onyx.api/start-peers! 10 pconfig)
        dturi (:datomic-uri config)]
    (d/create-database dturi)
    (d/transact (d/connect dturi) (ds/generate-schema d/tempid db-schema))
    (reset! cqrs/datomic-uri dturi)
    {:env env
     :pconfig pconfig
     :peers peers
     :job (onyx.api/submit-job
           pconfig
           {:catalog catalog :workflow cqrs/command-workflow :task-scheduler :onyx.task-scheduler/round-robin})}))

(defn stop-env [env]
  (onyx.api/kill-job (:pconfig env) (:job env))
  (doseq [p (:peers env)] (onyx.api/shutdown-peer p))
  (onyx.api/shutdown-env (:env env))
  (d/delete-database (:datomic-uri config))
  (doseq [c (:channels config)]
    (a/close! @(get config c))
    (reset! (get config c) nil))
  true)

(defn command [type data]
  (cqrs/command (d/basis-t (d/db (d/connect (:datomic-uri config)))) type data))

(defn send-command [type data]
  (kp/send-message (-> config :kafka :producer) (kp/message (:command-queue config) (.array (fressian/write (command type data))))))

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
        event (delay (first (a/alts!! [@(:event-store-stream config) (a/timeout 1000)])))
        aggregate (delay (first (a/alts!! [@(:aggregate-out-stream config) (a/timeout 1000)])))]
    (try
      (send-command :user/register {:name "Bob" :age 33})
      (println @event)
      (assert (= (:tp @event) :user/registered))
      (assert @aggregate)
      (assert (= #{["Bob" 33]} (d/q '[:find ?n ?a :where [?e :user/name ?n] [?e :user/age ?a]] (d/as-of (d/db (d/connect (:datomic-uri config))) (:t @aggregate)))))
      (finally
        (stop-env env)))))

(def kpconfig
  {"zookeeper.connect" "127.0.0.1:2181"
   "group.id" "onyx-consumer"
   "auto.offset.reset" "smallest"
   "auto.commit.enable" "true"})

(defn readq [queue]
  (k/with-resource [c (zk/consumer kpconfig)]
    zk/shutdown
    (println (first (map fressian/read (map :value (zk/messages c queue)))))))
