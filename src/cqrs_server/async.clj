(ns cqrs-server.async
  (:require
   [taoensso.timbre :as log]
   [clojure.core.async :as a]))

(defn writer [chan e s]
  (a/>!! chan s)
  [e])

(defn lifecycle [chan-atom]
  (fn [task-map]
    (log/info "Setup lifecycle:" task-map)
    (if (= (:onyx/type task-map) :function)
      (do
        (log/info "Function: " (:onyx/name task-map))
        {:core.async/chan @chan-atom
         :onyx.core/params [writer @chan-atom]})
      (do
        (log/info "In/out: " (:onyx/name task-map))
        {:core.async/chan @chan-atom}))))


(defn stream [type chan-atom]
  (let [base
        {:entry
         {:onyx/type type
          :onyx/batch-size 1
          :onyx/batch-timeout 500
          :onyx/max-peers 1}
         :lifecycle (lifecycle chan-atom)}]
    (if (#{:output :input} type)
      (->
       base
       (assoc-in
        [:entry :onyx/ident] (case type
                               :output :core.async/write-to-chan
                               :input :core.async/read-from-chan))
       (assoc-in
        [:entry :onyx/medium] :core.async))
      base)))
