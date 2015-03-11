(ns cqrs-server.dynamo
  (:require
   [taoensso.faraday :as far]))

;; A sample of the dynamodb credentials.
(def local-cred
  {:access-key "aws-access-key"
   :secret-key "aws-secret-key"
   :endpoint   "http://localhost:8000"
   :tablename :eventstore})

(defn table-setup
  "Setup dynamodb table

  Pass delete? true if you are testing and you want to remove the table before creation."
  [cred]
  (far/create-table
   cred (:tablename cred)
   [:id :s]
   {:range-keydef [:dt :n]
    :gsindexes [{:name :event-idx :hash-keydef [:tp :s] :range-keydef [:dt :n] :projection :all :throughput {:read 1 :write 1}}]
    :throughput {:read 1 :write 1}
    :block? true}))

(defn catalog [cred]
  {:onyx/ident :dynamodb/commit-tx
   :onyx/type :output
   :onyx/medium :dynamodb
   :onyx/consumption :concurrent
   :dynamodb/table (:tablename cred)
   :dynamodb/config cred
   :onyx/batch-size 100
   :onyx/doc "Transacts segments to dynamodb"})
