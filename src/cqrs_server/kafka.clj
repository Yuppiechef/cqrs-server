(ns cqrs-server.kafka
  (:require
   [clj-kafka.core :as k]
   [clj-kafka.producer :as kp]
   [clj-kafka.consumer.zk :as zk]
   [onyx.plugin.kafka]
))

(defn config
  [brokers zkaddress &
   {:keys [encoder partitioner]
    :or {encoder "kafka.serializer.DefaultEncoder"
         partitioner "kafka.producer.DefaultPartitioner"}} ]
  (let [config
        {:brokers brokers :zookeeper zkaddress
         :encoder encoder :partitioner partitioner}]
    (assoc
     config
     :producer
     (kp/producer
      {"metadata.broker.list" brokers
       "serializer.class" encoder
       "partitioner.class" partitioner}))))

(defn catalog [topic type groupid config]
  (let [base
        {:onyx/ident (case type :output :kafka/write-messages :kafka/read-messages)
         :onyx/medium :kafka
         :onyx/consumption :concurrent
         :kafka/topic topic
         :onyx/batch-size 1
         :onyx/doc "Reads messages from a Kafka topic"}]
    (case type
      :output
      (assoc
       base
       :kafka/brokers (:brokers config)
       :kafka/serializer-class (:encoder config)
       :kafka/partitioner-class (:partitioner config))
      (assoc
       base
       :kafka/zookeeper (:zookeeper config)
       :kafka/group-id groupid
       :kafka/offset-reset "smallest"))))

     

