(defproject cqrs-server "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies
  [[org.clojure/clojure "1.6.0"]
   [prismatic/schema "0.3.8-SNAPSHOT"]
   [danlentz/clj-uuid "0.0.7-SNAPSHOT"]
   
   [zookeeper-clj "0.9.3"]
   [org.slf4j/slf4j-api "1.7.7"]
   [org.clojure/data.json "0.2.3"]
   [org.apache.zookeeper/zookeeper "3.4.6"]
   [com.mdrogalis/onyx "0.5.2"]
   [com.mdrogalis/onyx-core-async "0.5.0"]
   [commons-codec "1.7"]
   [datomic-schema "1.2.2"]
   [org.clojure/core.async "0.1.346.0-17112a-alpha"]
   [criterium "0.4.3"]
   [yuppiechef/onyx-dynamodb "0.5.0"]
   [com.taoensso/faraday "1.5.0" :exclusions [org.clojure/clojure]]
   [com.mdrogalis/onyx-datomic "0.5.2"]
   [com.mdrogalis/onyx-kafka "0.5.0" :exclusions [org.slf4j/slf4j-simple]]
   [org.hornetq/hornetq-commons "2.4.0.Final"]
   [org.hornetq/hornetq-core-client "2.4.0.Final"]
   [org.hornetq/hornetq-server "2.4.0.Final"]]

  :profiles {:dev {:dependencies [[com.datomic/datomic-free "0.9.5130" :exclusions [org.slf4j/slf4j-nop org.slf4j/log4j-over-slf4j joda-time]]]}
             :prod {:dependencies [[com.datomic/datomic-pro "0.9.5130" :exclusions [org.slf4j/slf4j-nop org.slf4j/log4j-over-slf4j joda-time]]]}}
  :repl-options{:init-ns cqrs-server.core}
  :main cqrs-server.core)
