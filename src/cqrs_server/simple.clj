(ns cqrs-server.simple
  (:require
   [taoensso.timbre :as log]
   [clojure.core.async :as a]))

;; TODO: Exception handling


(defn resolve-fn [k]
  (cond
    (fn? k) k
    (keyword? k) (resolve (symbol (namespace k) (name k)))
    :else (throw (RuntimeException. "Unsupported function value: " k))))

(defn apply-function [f p x]
  (let [segments (apply f (concat p [x]))]
    (cond
      (map? segments) [segments]
      (coll? segments) segments
      :else [segments])))

(defmulti find-medium :onyx/medium)
(defmethod find-medium :default [_] nil)

(defmulti find-type :onyx/type)
(defmethod find-type :default [_] nil)

(defprotocol Task
  (setup [this inchan outchan]))

(deftype FunctionTask [task-map]
  Task
  (setup [this inchan outchan]
    (let [f (or (:onyx.core/fn task-map) (resolve-fn (:onyx/fn task-map)))
          p (or (:onyx.core/params task-map) (map (partial get task-map) (:onyx/params task-map)))]
      (a/pipeline 1 outchan (mapcat (partial apply-function f p)) inchan))))

(defmethod find-type :function [_] ->FunctionTask)

(deftype AsyncTask [task-map]
  Task
  (setup [this inchan outchan]
    (let [in (case (:onyx/type task-map) :input (:core.async/chan task-map) inchan)
          out (case (:onyx/type task-map) :output (:core.async/chan task-map) outchan)]
      (a/pipeline 1 out (map identity) in))))

(defmethod find-medium :core.async [_] ->AsyncTask)




(defn construct-task [env task]
  (let [name (:onyx/name task)
        lifecycle-fn (get-in env [:lifecycle name] (fn [_] {}))
        task (merge task (lifecycle-fn task))
        ctor (or (find-medium task) (find-type task))
        in-chan (a/chan 100)
        out-chan (a/chan 100)]
    (log/info "Constructing task: " (:onyx/name task) "-" task)
    {:name (:onyx/name task)
     :task (setup (ctor task) in-chan out-chan)
     :taskmap task
     :edges {:in in-chan :out out-chan :out-mult (a/mult out-chan)}}))

(defn construct-catalog [env catalog]
  (into {} (map (fn [t] [(:onyx/name t) (construct-task env t)]) catalog)))

(defn connect-edges [ctor-catalog [in-name out-name]]
  (let [in (get ctor-catalog in-name)
        out (get ctor-catalog out-name)
        _ (log/info "Tapping " in-name "->" out-name)
        tap (a/tap (-> in :edges :out-mult) (-> out :edges :in))]
    (update-in ctor-catalog [out-name :edges :in-tap] conj tap)))

(defn build-pipeline [ctor-catalog workflow]
  (reduce connect-edges ctor-catalog workflow))

(defn build-env [cqrs]
  {:lifecycle (reduce (fn [a [k v]] (if (:lifecycle v) (assoc a k (:lifecycle v)) a)) {} (:catmap cqrs))})

(defn start [cqrs]
  (let [env (build-env cqrs)
        ctor-catalog (construct-catalog env (:catalog cqrs))]
    (build-pipeline ctor-catalog (:workflow cqrs))))
