(ns cqrs-server.core
  (:require
   [datomic.api :as d]
   [datomic-schema.schema :as ds :refer [schema fields part]]
   [taoensso.faraday :as far]
   [clojure.core.async :as a]
   [clojure.data.fressian :as fressian]
   [clj-kafka.producer :as kp]
   [clj-kafka.consumer.zk :as zk]
   [clj-kafka.core :as k]
   [schema.core :as s]
   
   [onyx.plugin.datomic]
   [onyx.plugin.dynamodb]
   [onyx.plugin.core-async]
   [onyx.plugin.hornetq]
   [onyx.plugin.kafka]
   [onyx.queue.hornetq-utils :as hq-utils]
   [onyx.peer.task-lifecycle-extensions :as l-ext]
   [onyx.api]
   
   [cqrs-server.cqrs :as cqrs]
   [cqrs-server.module :as module]))

;; Start local dynamodb - download from:
;; http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Tools.DynamoDBLocal.html
;;  > java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar
;;
;; Download Kafka: http://kafka.apache.org/downloads.html
;; Unzip somewhere and in the root run both:
;;  > bin/zookeeper-server-start.sh config/zookeeper.properties
;;  > bin/kafka-server-start.sh config/server.properties
;;
;; In the cqrs-server project :
;;  > lein repl
;;
;; => (start)
;; "Setup complete"
;; => (send-command :user/register {:name "Bob" :age 31})
;; nil
;; => (d/q '[:find [?e ...] :where [?e :user/name]] (d/db (d/connect datomic-uri)))
;; [17592186045422]
;; => (map #(d/touch (d/entity (d/db (d/connect datomic-uri)) %)) *1)
;; ({:base/uuid #uuid "54d8fc2e-6c1f-4fb6-93f9-bef9536a9f7d", :user/age 31, :user/name "Bob", :db/id 17592186045422})

(defn dynamodb-setup [cred]
  (try
    (far/delete-table cred :events)
    (catch Exception e nil))
  (far/create-table
   cred :events
   [:id :s]
   {:range-keydef [:date :n]
    :gsindexes [{:name :event-idx :hash-keydef [:type :s] :range-keydef [:date :n] :projection :all :throughput {:read 1 :write 1}}]
    :throughput {:read 1 :write 1}
    :block? true}))

(def dynamodb-cred
  {:access-key "aws-access-key"
   :secret-key "aws-secret-key"
   :endpoint   "http://localhost:8000"})

(def kafka-producer
  (kp/producer
   {"metadata.broker.list" "127.0.0.1:9092"
    "serializer.class" "kafka.serializer.DefaultEncoder"
    "partitioner.class" "kafka.producer.DefaultPartitioner"}))

(def onyxid (java.util.UUID/randomUUID))
(def datomic-uri "datomic:mem://cqrs")
(def command-queue (str "command-queue"))
(def event-queue (str "event-queue"))
(def hornet {:host "localhost" :port 5465})
(def zookeeper {:address "127.0.0.1:2181"})
(def scheduler :onyx.job-scheduler/round-robin)


(def env-config
  {:hornetq/mode :standalone
   :hornetq/server? true
   :hornetq.server/type :embedded
   :hornetq.embedded/config ["hornetq/non-clustered-1.xml"]
   :hornetq.standalone/host (:host hornet)
   :hornetq.standalone/port (:port hornet)
   :zookeeper/address (:address zookeeper)
   :onyx/id onyxid
   :onyx.peer/job-scheduler scheduler})

(def peer-config
  {:hornetq/mode :standalone
   :hornetq.standalone/host (:host hornet)
   :hornetq.standalone/port (:port hornet)
   :zookeeper/address (:address zookeeper)
   :onyx/id onyxid
   :onyx.peer/inbox-capacity 1000
   :onyx.peer/outbox-capacity 1000
   :onyx.peer/job-scheduler scheduler})

(def catalog
  (cqrs/catalog
   {:ctx {:dburi datomic-uri}
    :command-queue
    {:onyx/ident :kafka/read-messages
     :onyx/medium :kafka
     :onyx/consumption :concurrent
     :kafka/topic command-queue
     :kafka/zookeeper (:address zookeeper)
     :kafka/group-id "onyx-consumer"
     :kafka/offset-reset "smallest"
     :onyx/batch-size 1
     :onyx/doc "Reads messages from a Kafka topic"}
    :out-event-queue
    {:onyx/ident :kafka/write-messages
     :onyx/medium :kafka
     :onyx/consumption :concurrent
     :kafka/topic event-queue
     :kafka/brokers "127.0.0.1:9092"
     :kafka/serializer-class "kafka.serializer.DefaultEncoder"
     :kafka/partitioner-class "kafka.producer.DefaultPartitioner"
     :onyx/batch-size 1
     :onyx/doc "Writes messages to a Kafka topic"}
    :in-event-queue
    {:onyx/ident :kafka/read-messages
     :onyx/medium :kafka
     :onyx/consumption :concurrent
     :kafka/topic event-queue
     :kafka/zookeeper (:address zookeeper)
     :kafka/group-id "onyx-consumer"
     :kafka/offset-reset "smallest"
     :onyx/batch-size 1
     :onyx/doc "Writes messages to a Kafka topic"}
    :event-store
    {:onyx/ident :dynamodb/commit-tx
     :onyx/type :output
     :onyx/medium :dynamodb
     :onyx/consumption :concurrent
     :dynamodb/table :events
     :dynamodb/config dynamodb-cred
     :onyx/batch-size 20
     :onyx/doc "Transacts segments to dynamodb"}
    :aggregate-store
    {:onyx/ident :datomic/commit-tx
     :onyx/type :output
     :onyx/medium :datomic-tx
     :onyx/consumption :concurrent
     :datomic/uri datomic-uri
     :datomic/partition :com.mdrogalis/people
     :onyx/batch-size 1000
     :onyx/doc "Transacts segments to storage"}}))



(defonce env (atom nil))
(defonce peers (atom nil))
(defonce jobs (atom []))


;; === Test implementation

(def db-schema
  (concat
   cqrs/db-schema
   [(schema
      base
      (fields
       [uuid :uuid :unique-identity]))]
   module/db-schema))

(defn command [type data]
  (cqrs/command (d/basis-t (d/db (d/connect datomic-uri))) type data))

(defn process-command [command]
  (->>
   command
   fressian/write
   (.array)
   (kp/message command-queue)
   (kp/send-message kafka-producer)))

(defn send-command [type data]
  (->>
   (command type data)
   process-command))

(defn start []
  (dynamodb-setup dynamodb-cred)

  (reset! cqrs/datomic-uri datomic-uri)
  (d/create-database datomic-uri)
  (d/transact (d/connect datomic-uri) (ds/generate-schema d/tempid db-schema))

  (reset! env (onyx.api/start-env env-config))
  (reset! peers (onyx.api/start-peers! 20 peer-config))
  (swap! jobs conj (onyx.api/submit-job peer-config {:catalog catalog :workflow cqrs/command-workflow :task-scheduler :onyx.task-scheduler/round-robin}))
  "Setup complete")

(defn stop []
  (doseq [j @jobs] (onyx.api/kill-job peer-config j))
  (reset! jobs [])
  (doseq [v-peer @peers]
    (onyx.api/shutdown-peer v-peer))
  (reset! peers nil)
  (onyx.api/shutdown-env @env)
  (reset! env nil))


;; Quickly try out some throughput..
(defn push-users [n]
  (doseq [c (take n (repeatedly (fn [] (cqrs/command :user/create {:user/name (str (java.util.UUID/randomUUID)) :user/age (int (rand 90))}))))]
    (kp/send-message kafka-producer (kp/message command-queue (.array (fressian/write c))))))

(def config
  {"zookeeper.connect" "127.0.0.1:2181"
   "group.id" "onyx-consumer"
   "auto.offset.reset" "smallest"
   "auto.commit.enable" "false"})

(defn readq [queue]
  (k/with-resource [c (zk/consumer config)]
    zk/shutdown
    (println (take 10 (map fressian/read (map :value (zk/messages c queue)))))))
