(ns cqrs-server.util
  (:require
   [clojure.tools.logging :as log]
   
   [schema.coerce :as coerce]
   [schema.macros :as macros]
   [schema.utils :as utils]
   [schema.core :as s])
  (:import
   [org.joda.time.format ISODateTimeFormat DateTimeFormat]
   [org.joda.time Instant]))

(defmacro with-resource
  [binding close-fn & body]
  `(let ~binding
     (try
       (do (log/info "Starting resource: " ~(pr-str body)) ~@body)
       (catch Exception e# (log/info "Exception" (str e#) (log/error e# "Problem inside with-resource")))
       (finally
         (log/info "closing resource: " ~(pr-str body))
         (~close-fn ~(binding 0))))))


(def dateformatters
  [(ISODateTimeFormat/dateTime)
   (.withZoneUTC (DateTimeFormat/forPattern "yyyy-MM-dd"))
   (.withZoneUTC (DateTimeFormat/forPattern "yyyy/MM/dd"))
   (.withZoneUTC (DateTimeFormat/forPattern "yyyy-MM-dd HH:mm:ss.S"))])

(defn parse-date [df d]
  (if (nil? df)
    nil
    (try
      (.toDate (Instant. (.parseMillis df d)))
      (catch Exception e nil))))

(def string->inst
  (coerce/safe
   #(try
      (let [n (Long/parseLong %)
            ;; Accomodate 'seconds' instead of 'millis'
           n (if (< n 100000000000) (* n 1000) n)]
        (.toDate (Instant. n)))
      (catch Exception e
        (loop [df (first dateformatters)
               r (rest dateformatters)]
          (let [p (parse-date df %)]
            (cond
              (nil? df) %
              (nil? p) (recur (first r) (rest r))
              :else p)))))))

(def Inst Instant)

(defn coercion-matcher [schema]
  (or
   (coerce/string-coercion-matcher schema)
   ({Inst string->inst} schema)))
