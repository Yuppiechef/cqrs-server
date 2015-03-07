(ns cqrs-server.async-test
  (:require
   [datomic.api :as d]
   [datomic-schema.schema :as ds :refer [schema fields part]]
   [schema.core :as s]
   [cqrs-server.module :as module]
   [cqrs-server.cqrs :as cqrs]
   [onyx.peer.task-lifecycle-extensions :as l-ext]
   
   [onyx.plugin.datomic]
   [onyx.plugin.core-async]
   [onyx.api]
   
   [clojure.core.async :as a]
   [clojure.test :refer :all]
   [taoensso.timbre :as log]))

;; A simple async cqrs test, with in-memory datomic

(def onyxid (java.util.UUID/randomUUID))

(def env-config
  {:hornetq/mode :vm
   :hornetq/server? true
   :hornetq.server/type :vm
   :zookeeper/address "127.0.0.1:2185"
   :zookeeper/server? true
   :zookeeper.server/port 2185
   :onyx/id onyxid
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def peer-config
  {:hornetq/mode :vm
   :zookeeper/address "127.0.0.1:2185"   
   :onyx/id onyxid
   :onyx.peer/inbox-capacity 100
   :onyx.peer/outbox-capacity 100
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def config
  {:datomic-uri "datomic:mem://cqrsasync"
   :command-stream (atom nil)
   :event-stream (atom nil)
   :event-store-stream (atom nil)
   :aggregate-out-stream (atom nil)
   :channels [:command-stream :event-stream :event-store-stream :aggregate-out-stream]
   :env env-config
   :peer peer-config
   :onyxid onyxid})

(defn chan-stream [type]
  {:onyx/ident (if (= type :outpu) :core.async/write-to-chan :core.async/read-from-chan)
   :onyx/type type
   :onyx/medium :core.async
   :onyx/consumption :sequential
   :onyx/batch-size 1
   :onyx/batch-timeout 500
   :onyx/max-peers 1})

(def catalog
  (cqrs/catalog
   {:command-queue (chan-stream :input)
    :out-event-queue (chan-stream :output)
    :in-event-queue (chan-stream :input)
    :event-store (chan-stream :output)
    :aggregate-out (chan-stream :output)}))

(defmethod l-ext/inject-lifecycle-resources :command/in-queue [_ _]
  {:core-async/in-chan @(:command-stream config)})

(defmethod l-ext/inject-lifecycle-resources :event/out-queue [_ _]
  {:core-async/out-chan @(:event-stream config)})

(defmethod l-ext/inject-lifecycle-resources :event/in-queue [_ _]
  {:core-async/in-chan @(:event-stream config)})

(defmethod l-ext/inject-lifecycle-resources :event/store [_ _]
  {:core-async/out-chan @(:event-store-stream config)})

(defmethod l-ext/inject-lifecycle-resources :event/aggregate-out [_ _]
  {:core-async/out-chan @(:aggregate-out-stream config)})

(defn setup-env [db-schema]
  (doseq [c (:channels config)]
    (reset! (get config c) (a/chan 10)))
  (let [env (onyx.api/start-env env-config)
        peers (onyx.api/start-peers! 10 peer-config)
        dturi (:datomic-uri config)]
    (d/create-database dturi)
    (d/transact (d/connect dturi) (ds/generate-schema d/tempid db-schema))
    (reset! cqrs/datomic-uri dturi)
    {:env env
     :peers peers
     :job (onyx.api/submit-job
           peer-config
           {:catalog catalog :workflow cqrs/command-workflow :task-scheduler :onyx.task-scheduler/round-robin})}))

(defn stop-env [env]
  (onyx.api/kill-job peer-config (:job env))
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
        event (delay (first (a/alts!! [@(:event-store-stream config) (a/timeout 1000)])))
        aggregate (delay (first (a/alts!! [@(:aggregate-out-stream config) (a/timeout 1000)])))]
    (try
      (send-command :user/register {:name "Bob" :age 33})
      (assert (= (:type @event) :user/registered))
      (assert @aggregate)
      (assert (= #{["Bob" 33]} (d/q '[:find ?n ?a :where [?e :user/name ?n] [?e :user/age ?a]] (d/as-of (d/db (d/connect (:datomic-uri config))) (:basis-t @aggregate)))))
      (finally
        (stop-env env)))))
