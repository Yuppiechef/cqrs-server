(ns cqrs-server.cqrs
  (:require
   [cqrs-server.util :as util]
   [clojure.core.async :as a]
   [clojure.tools.logging :as log]
   [taoensso.nippy :as nippy]
   [schema.core :as s]
   [schema.coerce :as coerce]
   [clj-uuid :as u]
   [clojure.java.data :as data]))

;; TODO - tag code version onto the events.

(defn error
  "Produce an error segment, level should be one of :dev, :user, :system.

  :dev - code bug, send to DLQ - this is the default error.
  :user - user error, provide feedback
  :system - system error (network problems, file writing issues, etc), apply retry strategy and DLQ if not"
  [level msg & {:keys [tag data] :as opts}]
  {:cid (or (-> data :cmd :cid ) (-> data :ev :cid))
   :error/msg msg
   :error/opts opts
   :error/level level})

(defmulti aggregate-event (fn [{:keys [tp] :as event}] tp))

(defmethod aggregate-event :default [_] [])

(defn aggregate-event* [writer-fn & args]
  (try
    (let [e (last args)
          _ (log/info "Aggregating" e)
          r (aggregate-event e)]
      (apply writer-fn (concat (butlast args) [e r])))
    (catch Exception ex
        [(error :dev (.getMessage ex) :tag :aggregate-event :data
                {:ex (data/from-java ex) :ev (last args)})])))

(defmulti command-coerce (fn [{:keys [ctp] :as command}] ctp))

(defmethod command-coerce :default [{:keys [ctp] :as command}]
  (error :dev (str "command-coerce for " ctp " has not been defined.") :tag :command-coerce :data {:cmd command}))

(defn command-coerce* [c]
  (log/info "Coercing: " c)
  (try
    (let [coerced (command-coerce c)]
      (cond
        (:error/msg coerced)
        [coerced]
        (:error coerced)
        [(error :user "Command validation error" :tag :validation :data {:validation (:error coerced) :cmd c})]
        :else
        [(assoc c :data coerced)]))
    (catch Exception ex
        [(error :dev (.getMessage ex) :tag :command-coerce :data
                {:ex (data/from-java ex) :cmd c})])))


(defn install-command [[type schema]]
  `(let [coercer# (coerce/coercer (assoc ~schema s/Any s/Any) util/coercion-matcher)]
     (defmethod command-coerce ~type [c#]
       (coercer# (:data c#)))))

(defmacro install-commands [commands]
  (concat [(symbol "do")] (map install-command commands)))



(defmulti process-command (fn [{:keys [ctp] :as command}] ctp))

(defmethod process-command :default [{:keys [ctp] :as c}]
  [(error :dev (str "process-command for " ctp " has not been defined.") :tag :process-command :data {:cmd c})])

(defn process-command* [& args]
  (try
    (let [command (last args)
          _ (log/info "Processing Command: " command)
          _ (log/info "args: " args)
          kpairs (butlast args)
          result
          (process-command (if (-> kpairs count zero?) command (apply assoc command (butlast args))))]
      (log/info "Processed Command: " result)
      result)
    (catch Exception ex
        [(error :dev (.getMessage ex) :tag :process-command :data
                {:ex (data/from-java ex) :cmd (last args)})])))

(defn event-store [& [writer-fn & args :as a]]
  (try
    (log/info "EVENT STORE: " a)
    (let [e (last args)
          _ (log/info "Event: " e)
          s (assoc e :id (str (:id e)) :cid (str (:cid e)) :data (nippy/freeze (:data e)))]
      (log/info "Prepared for storage: " s)
      (apply writer-fn (concat (butlast args) [e s])))
    (catch Exception ex
        [(error :dev (.getMessage ex) :tag :event-store :data
                {:ex (data/from-java ex) :ev (last args)})])))


(defn command [code-sha basis-t type data]
  {:t basis-t
   :sha (or code-sha "NA")
   :ctp type
   :cid (str (java.util.UUID/randomUUID))
   :data data})

(defn event [command segment n [type data]]
  {:id (u/v5 u/+namespace-oid+ (str (:cid command) ":" n "/" segment))
   :tp type
   :sha (or (:sha command) "NA")
   :cid (:cid command)
   :ctp (:ctp command)
   :dt (.getTime (java.util.Date.))
   :t (:t command) 
   :data data})

(defn events [command segment msgs]
  (map (partial event command segment) (range) msgs))

(def command-workflow
  [[:command/in-queue :command/coerce]
   [:command/coerce :command/process]
   [:command/process :event/out-queue]
   [:event/in-queue :event/store]
   [:event/store :event/aggregator]
   [:event/aggregator :command/feedback]])



(comment
  ;; Failure handling
  [[:command/coerce :error]
   [:command/process :error]
   [:event/store :error]
   [:event/aggregator :error]
   [:event/aggregate-store :error]])

(defn handle-error [& [writer-fn & args :as a]]
  (log/info "Error handle: " a)
  (apply writer-fn (concat args [(last a)]))
  [])

(defn error? [segment]
  (contains? segment :error/msg))

(def not-error? (complement error?))

(defn error-flows [workflow catalog]
  (concat
   (map
    (fn [[f t]]
      {:conditions
       [{:flow/from f
         :flow/to [t]
         :flow/predicate :cqrs-server.cqrs/not-error?}]})
    workflow)
   (map
    (fn [c]
      {:conditions
       [{:flow/from (:onyx/name c)
         :flow/to [:cqrs/error]
         :flow/predicate :cqrs-server.cqrs/error?}]
       :workflow
       [[(:onyx/name c) :cqrs/error]]})
    catalog)))

(defn catalog
  [c]
  [(assoc
    (-> c :command/in-queue :entry)
    :onyx/name :command/in-queue
    :onyx/type :input
    :onyx/batch-size 1)

   {:onyx/name :command/coerce
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/command-coerce*
    :onyx/batch-size 1}

   (assoc
    (-> c :command/process :entry)
    :onyx/name :command/process
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/process-command*
    :onyx/batch-size 1)

   (assoc
    (-> c :event/out-queue :entry)
    :onyx/name :event/out-queue
    :onyx/type :output
    :onyx/batch-size 1)

   (assoc
    (-> c :event/in-queue :entry)
    :onyx/name :event/in-queue
    :onyx/type :input
    :onyx/batch-size 1)
   
   (assoc
    (-> c :event/store :entry)
    :onyx/name :event/store
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/event-store
    :onyx/batch-size 1)

   (assoc
    (-> c :event/aggregator :entry)
    :onyx/name :event/aggregator
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/aggregate-event*
    :onyx/batch-size 1)

   (assoc
    (-> c :command/feedback :entry)
    :onyx/name :command/feedback
    :onyx/type :output
    :onyx/batch-size 1)

   (assoc
    (-> c :cqrs/error :entry)
    :onyx/name :cqrs/error
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/handle-error
    :onyx/batch-size 1)])



(defn setup [onyxid catalog-map]
  (let [c (catalog catalog-map)
        flows (error-flows command-workflow (remove #(= (:onyx/name %) :cqrs/error) c))]
    {:catalog (catalog catalog-map)
     :workflow (concat command-workflow (mapcat :workflow flows))
     :conditions (mapcat :conditions flows)
     :onyxid onyxid
     :catmap catalog-map}))
