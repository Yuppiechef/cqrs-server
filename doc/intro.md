# Introduction to _cqrs-server_

An opinionated CQRS/ES implementation using [Onyx](https://github.com/MichaelDrogalis/onyx), [Datomic](http://www.datomic.com/), [DynamoDB](http://aws.amazon.com/dynamodb/), [Kafka](http://kafka.apache.org/) and [Zookeeper](http://zookeeper.apache.org/).

## The problem

Best to start with the core problem at hand:

> The current relational database is too limiting. We're dropping all sorts of interesting data on the ground because we don't have suitable pigeonholes to put it into.

Any system that has enough interesting interaction happening to it faces this problem. The data that we do end up putting into our database tends to inconsistent design. It is then risky to change down the line because it gets treated as our _[system of record](http://en.wikipedia.org/wiki/System_of_record)_.

To this end, we've been doing research into _Event Sourcing_ (ES). This led to _Command/Query Responsibility Segregation_ (CQRS) and touched on the area of _Domain Driven Design_ (DDD).

## Introduction to Event Sourcing (ES)

An event is a past tense archive record about what has happened. Examples are `UserLoggedIn`, `AddedToCart`, `CheckedOut`, `OrderShipped`, `OrderDelivered` and `ProductFaultLogged`.

The idea is that if you maintain a list of these events, you have a canonical source that you can build arbitrary aggregate views from. You can completely obviate the need for cache invalidation - Treat the events as a stream and update all the various sources as events filter through.

The past tense is important to distinguish Events from Commands like `UserLogin` or `AddToCart`. A Command implies that the user is attempting an action - the system can still make some kind of decision, validation or even refusal to process. This is not the case in past tense events as you cannot mutate history.

You store the list of events into an Event Store and use that as the canonical state of the system. You derive views in other shapes optimized for reading. 

A key benefit is that you can introduce new derived views. Leverage existing events and populate the views with historical data as if they were always there.


## Introduction to Command/Query Responsibility Segregation

Most of the reading material will describe CQRS through Object Oriented lenses. This introduces all sorts complexity - a lot to do with command responsibility managers and logical aggregate objects. We've distilled the essence of CQRS into:

> "Separate your writes and queries"

This implies that you create an archive in the best form for writes and a separate storage convenient for reads.

While we're distilling things, let's look at some useful word definitions (courtesy of [Merriam Webster Dictionary](http://www.merriam-webster.com)):

> **Command (verb)**: to direct authoritatively
>
> **Query (noun)**: a question or a request for information about something
>
> **Aggregate (adj)**: formed by the collection of units or particles into a body, mass, or amount

The Command has a very distinct role in that it can make decisions about what events end up happening. `RegisterUser`, for example, might produce a `UserRegistered` or `UserRegistrationFailed` event.

The impedance mismatch usually associated with relational databases diminishes when you've split your database into a write-optimized archive and various read-optimized aggregates.

## Implementation specifics

![CQRS Diagram](https://www.lucidchart.com/publicSegments/view/54daedae-735c-48b6-a31a-41210a0082c6/image.png)

### Onyx

Onyx is a masterless distributed computation system. Effectively becoming the glue of the system and managing connections between the components.

It directs an arbitrary number of peers and allows the _cqrs-server_ to scale out by adding new nodes. The configuration is also flexible. It's simple to replace Kafka with another queueing mechanism or introduce more complex batch jobs.

The nature of Onyx is that it's a distributed system. Thus, we have to assume that duplicate Commands and Events will process. We need to ensure that side-effecting changes are idempotent. _cqrs-server_ achieves this by doing a few things under the hood:

 - Attach a uuid to the command before sending it into the queue
 - Attach a current basis-t of the datomic database value as at command generation
 - Derive event uuids from the command uuid ([namespaced v5 uuids](https://github.com/danlentz/clj-uuid/blob/86c9feb84c2175466f1c2784b3f740f523a84302/src/clj_uuid.clj#L321-L326)) so that they are deterministic
 - Ensure that the event store takes care of duplicate event writes
 - Ensure that the aggregate store treats duplicate event transactions as no-ops

With Onyx we can define the workflow:

![Workflow, visualized](https://www.lucidchart.com/publicSegments/view/54daef70-57e8-4898-8d7d-12390a0082c6/image.png)

In code, this is just a vector of tuples:
```clojure
(def command-workflow
 [[:command/in-queue :command/coerce]
   [:command/coerce :command/process]
   [:command/process :event/out-queue]
   [:event/in-queue :event/prepare-store]
   [:event/prepare-store :event/store]
   [:event/in-queue :event/aggregator]
   [:event/aggregator :event/store-aggregate]])
```

This gives Onyx enough information to know where to send batches, but not enough to know what the places are. For that, it needs a catalog. In the case of _cqrs-server_ it looks like: _(we've elided a lot specifics for the clarity)_

```clojure
=> (pprint catalog)
[{:onyx/name :command/in-queue,
  :onyx/medium :kafka,
  :onyx/ident :kafka/read-messages,
  :onyx/type :input,
  :kafka/zookeeper "127.0.0.1:2181",
  :kafka/topic "command-queue"}
  
 {:onyx/name :command/coerce,
  :onyx/type :function,
  :onyx/fn :cqrs-server.cqrs/command-coerce*}
  
 {:onyx/name :command/process,
  :onyx/type :function,
  :onyx/fn :cqrs-server.cqrs/process-command*}
  
 {:onyx/name :event/out-queue,
  :onyx/medium :kafka,
  :onyx/ident :kafka/write-messages,
  :onyx/type :output,
  :kafka/topic "event-queue",
  :kafka/brokers "127.0.0.1:9092"}
  
 {:onyx/name :event/in-queue,
  :onyx/ident :kafka/read-messages,
  :onyx/medium :kafka,
  :onyx/type :input,
  :kafka/zookeeper "127.0.0.1:2181",
  :kafka/topic "event-queue"}
  
 {:onyx/name :event/prepare-store,
  :onyx/type :function,
  :onyx/fn :cqrs-server.cqrs/prepare-store}
  
 {:onyx/name :event/store,
  :onyx/ident :dynamodb/commit-tx,
  :onyx/type :output,
  :onyx/medium :dynamodb,
  :dynamodb/table :events,
  :dynamodb/config
  {:access-key "aws-access-key",
   :secret-key "aws-secret-key",
   :endpoint "http://localhost:8000"}}
   
 {:onyx/name :event/aggregator,
  :onyx/type :function,
  :onyx/fn :cqrs-server.cqrs/aggregate-event*}
  
 {:onyx/name :event/store-aggregate,
  :onyx/ident :datomic/commit-tx,
  :onyx/type :output,
  :onyx/medium :datomic-tx,
  :datomic/uri "datomic:mem://cqrs"}]
```

Onyx manages the lifecycle of each of the components defined in the catalog. As messages progress through the workflow it uses the `:onyx/name` to lookup the component.

### Apache Kafka

Kafka is a high-throughput, log-based publish-subscribe messaging system.

It holds on to the messages after consumption. This makes it well suited for failure recovery by replaying the recent commands or events. In the diagram above Kafka is the implementation of the Command and Event queues.

### Amazon DynamoDB

DynamoDB is a flexible and scalable K/V store which we're using as the Event Store. It doesn't matter where this goes as long as it's reliable and allows you to query a subset of the events by a given date range.

To take care of duplicates, we rewrite the same event into Dynamo using the event uuid as a key. This will overwrite the duplicate event, but won't change anything.

### Datomic

Datomic is a transactional, distributed Entity, Attribute, Value, Tx (EAVT) database. It provides expressive querying facilities, has a single-node transactor and near-arbitrary read scalability.

The single-node transactor is key to de-duplicating the event aggregation. We created an `:idempotent-tx` transactor function that checks:

 - If a tx is _already_ tagged with the given `:event/uuid`, convert tx to a no-op
 - If a tx is _not_ tagged, tag this tx with the `:event/uuid` and commit.

Another concern is that of duplicated commands. A command could produce a different set of events when one set of aggregates reaches the transactor before it gets to run.

To prevent inconsistent results, we tag the command with the current `basis-t`. This roots our command to a specific immutable database value.  The command should then always evaluate to the same result if it makes decisions based on the Datomic aggregate as of `basis-t`.

## Running _cqrs-server_

We've covered some of the larger design decisions. Let's delve into the _cqrs-server_ specific implementation.

First, download and unzip dynamodb local from <http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Tools.DynamoDBLocal.html> and run:

```bash
java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar
```

Next, download Kafka from <http://kafka.apache.org/downloads.html>, unzip and run both:
```bash
bin/zookeeper-server-start.sh config/zookeeper.properties
bin/kafka-server-start.sh config/server.properties
```
Finally, clone _cqrs-server_ and run:
```bash
git clone https://github.com/Yuppiechef/cqrs-server.git
cd cqrs-server
lein repl
```
After a bit of compiling you should have a REPL waiting, run the following function:
```clojure
(start)
```
It should give you a `"Setup Complete"` message once it's done and ready to start taking commands. Go open the `src/cqrs_server/module.clj` file and have a look.

## Playing around
The `module.clj` should give you a feel for roughly how you would add your own logic into the system:
 - Define an aggregate schema for datomic
 - Install the command schema's 
 - Implement `cqrs/process-command` for each command
 - Implement `cqrs/aggregate-event` for each event

`cqrs/aggregate-event` is optional. You will notice that there is not aggregation for the `:user/register-failed` event. This will still record the event in Dynamo, but have no read view.

_**Quick aside**: For convenience, we will be using the `=>` in the samples below to show the REPL prompt and prefix the result of the calls with `;;`_

Back in your REPL, try register a user and check that he exists:
```clojure
=> (send-command :user/register {:name "Bob" :age 31})
```
This will fire off the user registration - check that it worked:
```clojure
=> (d/q '[:find [?e ...] :where [?e :user/name]] (d/db (d/connect datomic-uri)))
;; [17592186045422]
=> (map #(d/touch (d/entity (d/db (d/connect datomic-uri)) %)) *1)
;; ({:base/uuid #uuid "54d8fc2e-6c1f-4fb6-93f9-bef9536a9f7d", :user/age 31, :user/name "Bob", :db/id 17592186045422})
```
You can then check your dynamodb event store:
```clojure
=> (far/scan dynamodb-cred :events)
;; [{:date 1423510153513N, :data #<byte[] ...>, :basis-t 1000N, :id "be856c9c-0bf8-5ccc-bec1-bfa0f5a7e983", :type "user/registered"}]
```

You can go ahead and play around with sending the other commands in the `module.clj` and see how they affect things. Also try create new commands and event aggregators - there's not much to it.

## Where's my query?

The astute reader will notice that this discussion has focussed on the Command part of CQRS and not so much on the Query part. This is because once you have your aggregate view, you're done.

Any part of your system that needs to read can directly consume the aggregate views with no need to interact with the _cqrs-server_.

## Conclusion

We have shown a functional distillation of CQRS. We've composed various pieces of software to build a solid foundation for a flexible distributed system. _cqrs-server_ provides the basic framework needed for a CQRS-based system.

## Further Reading
Some related reading that influenced this design, in no particular order:

 - [CQS on Wikipedia](http://en.wikipedia.org/wiki/Command-query_separation)
 - [CQRS Documents by Greg Young](https://cqrs.files.wordpress.com/2010/11/cqrs_documents.pdf)
 - [Martin Fowler: Command Query Separation](http://martinfowler.com/bliki/CommandQuerySeparation.html)
 - [Event Sourcing Basics](http://docs.geteventstore.com/introduction/event-sourcing-basics/)
 - [Event Sourcing for Functional Programmers](http://danielwestheide.com/talks/flatmap2013/slides/index.html)
 - [Martin Fowler: Event Sourcing](http://martinfowler.com/eaaDev/EventSourcing.html)
 - [DDD, Event Sourcing, and CQRS Tutorial: design](http://cqrs.nu/tutorial/cs/01-design)
 - [CQRS Journey](http://msdn.microsoft.com/en-us/library/jj554200.aspx)
  