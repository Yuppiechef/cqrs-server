(defproject cqrs-server "0.1.1-SNAPSHOT"
  :description "Implementation of a simple CQRS server using Onyx"
  :url "https://github.com/Yuppiechef/cqrs-server"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies
  [[org.clojure/clojure "1.7.0-alpha5"]
   [prismatic/schema "0.4.0"]
   [danlentz/clj-uuid "0.1.5"]
   [com.taoensso/nippy "2.8.0"]
   [org.clojure/data.json "0.2.5"]
   [commons-codec "1.9"]
   [org.clojure/core.async "0.1.346.0-17112a-alpha"]
   [joda-time/joda-time "2.7"]]
  :profiles
  {:datomic-free
   {:dependencies
    [[com.datomic/datomic-free "0.9.5153" :exclusions [org.slf4j/slf4j-nop org.slf4j/log4j-over-slf4j]]
     [datomic-schema "1.2.2"]]}
   :datomic-pro
   {:dependencies
    [[com.datomic/datomic-pro "0.9.5153" :exclusions [org.slf4j/slf4j-nop org.slf4j/log4j-over-slf4j]]
     [datomic-schema "1.2.2"]]}
   :dev
   {:dependencies
    [
     [org.slf4j/slf4j-api "1.7.7"]
     [zookeeper-clj "0.9.3"]
     [org.apache.zookeeper/zookeeper "3.4.6"]
     [com.mdrogalis/onyx "0.6.0-SNAPSHOT"]
     [yuppiechef/onyx-dynamodb "0.5.0"]
     [com.taoensso/faraday "1.5.0" :exclusions [org.clojure/clojure joda-time]]
     [com.mdrogalis/onyx-datomic "0.5.2"]
     [criterium "0.4.3"]
     [com.mdrogalis/onyx-kafka "0.5.3" :exclusions [org.slf4j/slf4j-simple]]]}}
  #_:repl-options #_{ :init-ns cqrs-server.core :timeout 120000}
  #_:main #_cqrs-server.core)
