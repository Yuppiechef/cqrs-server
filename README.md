# cqrs-server

An opinionated CQRS/ES implementation using Onyx, Datomic, DynamoDB, Kafka and Zookeeper.

## Usage

A quick guide to get started :

### Install dynamodb local

Get dynamodb local from: http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Tools.DynamoDBLocal.html

And then run:
```
java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar
```

### Kafka & Zookeeper

Download and extract Kafka: http://kafka.apache.org/downloads.html

Run both these (probably in separate terminals)
```
bin/zookeeper-server-start.sh config/zookeeper.properties
bin/kafka-server-start.sh config/server.properties
```

Currently, this needs the latest SNAPSHOT version of Prismatic Schema, so clone that repo and install the jar:

```
git clone https://github.com/Prismatic/schema.git
cd schema
lein cljx once
lein install
```

### cqrs-server

Then clone this repo and fire it up!

```
cd cqrs-server
lein repl

=> (start)
"Setup complete"
=> (send-command :user/register {:name "Bob" :age 31})
nil
=> (d/q '[:find [?e ...] :where [?e :user/name]] (d/db (d/connect datomic-uri)))
[17592186045422]
=> (map #(d/touch (d/entity (d/db (d/connect datomic-uri)) %)) *1)
({:base/uuid #uuid "54d8fc2e-6c1f-4fb6-93f9-bef9536a9f7d", :user/age 31, :user/name "Bob", :db/id 17592186045422})
```

Now we have a user in the system, let's fill out his profile a bit:

```
=> (send-command :user/update-email {:uuid #uuid "54d8fc2e-6c1f-4fb6-93f9-bef9536a9f7d" :email "bob@example.com"})
=> (send-command :user/disabled {:uuid #uuid "54d8fc2e-6c1f-4fb6-93f9-bef9536a9f7d"})
=> (map #(d/touch (d/entity (d/db (d/connect datomic-uri)) %)) (d/q '[:find [?e ...] :where [?e :user/name]] (d/db (d/connect datomic-uri))))
({:base/uuid #uuid "54d90a89-0880-4f30-bb34-42f29ceb1095", :user/age 31, :user/email "bob@example.com", :user/name "Bob", :user/status :user.status/disabled, :db/id 17592186045422})
```

We can also send some pageviews and see how it updates the viewcount on the user (a possibly useful aggregate):

```
=> (send-command :user/pageview {:uuid #uuid "54d90a89-0880-4f30-bb34-42f29ceb1095" :url "http://www.example.com" :render-time 230})
=> (send-command :user/pageview {:uuid #uuid "54d90a89-0880-4f30-bb34-42f29ceb1095" :url "http://www.example.com" :render-time 212})
=> (send-command :user/pageview {:uuid #uuid "54d90a89-0880-4f30-bb34-42f29ceb1095" :url "http://www.example.com" :render-time 182})
=> (map #(d/touch (d/entity (d/db (d/connect datomic-uri)) %)) (d/q '[:find [?e ...] :where [?e :user/name]] (d/db (d/connect datomic-uri))))
({:base/uuid #uuid "54d90a89-0880-4f30-bb34-42f29ceb1095", :user/age 31, :user/email "bob@example.com", :user/name "Bob", :user/status :user.status/disabled, :user/viewcount 3, :db/id 17592186045422})
```

Then lets have a look at the events:

```
=> (far/scan dynamodb-cred :events)
[{:date 1423510307575N, :data #<byte[] ...>, :basis-t 1008N, :id "86439637-8f1e-5170-9b23-824486e3506a", :type "user/pageviewed"} {:date 1423510178427N, :data #<byte[] ...>, :basis-t 1005N, :id "c67ccc74-c71c-5578-80ad-924c470f052f", :type "user/email-updated"} {:date 1423510316827N, :data #<byte[] ...>, :basis-t 1010N, :id "08316c9b-3fcd-5a9f-b095-4bf0c1a61a05", :type "user/pageviewed"} {:date 1423510210618N, :data #<byte[] ...>, :basis-t 1007N, :id "46ac00c9-bd7d-5903-91e0-af56d28ef751", :type "user/disabled"} {:date 1423510153513N, :data #<byte[] ...>, :basis-t 1000N, :id "be856c9c-0bf8-5ccc-bec1-bfa0f5a7e983", :type "user/registered"} {:date 1423510312463N, :data #<byte[] ...>, :basis-t 1009N, :id "5c2eb804-1016-5fa3-a868-c01b515f980d", :type "user/pageviewed"}]
```

The actual data is fressian encoded so that there's no pain with the transformation of clojure data structures.

NOTE: If you do actually use this for user aggregates and authentication, remember to at least bcrypt your passwords.
Be very aware that sensitive data is written to multiple places in this system: the kafka queues, the dynamo event source and the datomic aggregate. This is a particularly important consideration for things like credit card details and passwords.

## Datomic-pro

The default profile uses datomic-free - if you want to use datomic-pro, start the repl with `lein with-profile prod`

When you use cqrs-server as a library to your application project, you'll probably want to add it as a dependency with an exclusion: `[cqrs-server "0.1.0-SNAPSHOT" :exclusions [com.datomic/datomic-free]]`

## Tests

Because this project depends on correct integration with kafka, dynamodb and zookeeper, the tests require that you have these running locally before you run `lein test`. See above in order to get them installed and started.

## License

Copyright © 2015 Yuppiechef Online (Pty) Ltd.

Distributed under The MIT License (MIT) - See LICENSE.txt
