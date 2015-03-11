(ns cqrs-server.async
  (:require
   [clojure.core.async :as a]
   [onyx.peer.task-lifecycle-extensions :as l-ext]
   [onyx.plugin.core-async]))

(defn chan-stream [type]
  {:onyx/ident (if (= type :output) :core.async/write-to-chan :core.async/read-from-chan)
   :onyx/type type
   :onyx/medium :core.async
   :onyx/consumption :sequential
   :onyx/batch-size 1
   :onyx/batch-timeout 500
   :onyx/max-peers 1})

(defmacro chan-register [ident type chan-atom]
  `(defmethod l-ext/inject-lifecycle-resources ~ident [~(symbol "_") ~(symbol "_")]
     {~(if (= type :output) :core-async/out-chan :core-async/in-chan) (deref ~chan-atom)}))
