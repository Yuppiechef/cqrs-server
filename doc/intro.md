# Introduction to cqrs-server

An opinionated CQRS/ES implementation using [Onyx](https://github.com/MichaelDrogalis/onyx), [Datomic](http://www.datomic.com/), [DynamoDB](http://aws.amazon.com/dynamodb/), [Kafka](http://kafka.apache.org/) and [Zookeeper](http://zookeeper.apache.org/).

## The problem

Best to start with the core problem at hand:

> The current relational database is too limiting. We're dropping all sorts of interesting data on the ground because we don't have suitable pigeonholes to put it into.

Any system that has enough interesting interaction happening to it faces this problem. The data that we do end up putting into our database tends to all be designed slightly inconsistently. Then it's incredibly risky to change down the line because it gets treated as our _[system of record](http://en.wikipedia.org/wiki/System_of_record)_.

To this end, we've been doing research into _Event Sourcing_ (ES). This led to _Command/Query Responsibility Segregation_ (CQRS) and touched on the area of _Domain Driven Design_ (DDD).

## Introduction to Event Sourcing (ES)

An event is a past tense archive record about what has happened. Examples are `UserLoggedIn`, `AddedToCart`, `CheckedOut`, `OrderShipped`, `OrderDelivered` and `ProductFaultLogged`.

The idea is that if you maintain a list of these events, you have a canonical source that you can build arbitrary aggregate views from. You can completely obviate the need for cache invalidation if you treat these events as a stream and update all the various sources as events filter through.

The past tense is very important to distinguish Events from Commands like `UserLogin` or `AddToCart`. The Commands imply that the user is attempting an action and that the system can still make some kind of decision, validation or even refusal to process. This is not the case in past tense events as you cannot mutate history.

You store the list of events into what is called an Event Store and use that as the canonical state of the system. You derive other views in other shapes that are optimized for reading. 

A key benefit is that you can introduce new derived views. Simply leverage existing events and populate the views with historical data as if they were always there.


## Introduction to Command/Query Responsibility Segregation

Most of the reading material out there will describe CQRS through very Object Oriented lenses and introduce all sorts of management layers, a lot to do with command responsibility managers and logical aggregate objects. We've distilled the essence of CQRS into:

> "Separate your writes and queries"

This implies that you create an archive in the most convenient form for your writes and a separate storage convenient for reads.

While we're distilling things, let's look at some useful word definitions (courtesy of [Merriam Webster Dictionary](http://www.merriam-webster.com)):

> **Command (verb)**: to direct authoritatively
>
> **Query (noun)**: a question or a request for information about something
>
> **Aggregate (adj)**: formed by the collection of units or particles into a body, mass, or amount

The Command has a very distinct role in that it can make decisions about what events end up happening. `RegisterUser`, for instance, might produce a `UserRegistered` or `UserRegistrationFailed` event depending on the logic inside the command.

The impedance mismatch generally associated with relational databases diminishes severely when you've split your database into a write-optimized archive and various read-optimized aggregates.

## Implementation specifics

![CQRS Diagram](https://www.lucidchart.com/publicSegments/view/54da0b1b-bf78-4ec6-8256-78a10a00816a/image.png)

### Onyx

Onyx is a masterless distributed computation system. Effectively becoming the glue of the system and managing connections between the components.

It directs an arbitrary number of peers and allows the cqrs-server to scale out by simply adding new nodes. The configuration is also incredibly flexible, making it simple to replace Kafka with another queueing mechanism or introduce more complex batch jobs.

The nature of Onyx is that it's a distributed system. We have to assume that duplicate Commands and Events will be processed so we need to ensure that side-effecting changes are made idempotently. This is achieved in _cqrs-server_ by doing a few things under the hood:

 - Attach a uuid to the command before sending it into the queue
 - Attach a current basis-t of the datomic database value as at command generation
 - Derive uuids for the events based on the command uuid (v5 uuids) so that the event uuids are fully deterministic
 - Ensure that the event store takes care of duplicate event writes
 - Ensure that the aggregate store treats duplicate event transactions as no-ops

Onyx plays the most crucial part in making cqrs-server as flexible and concise as it is.

### Apache Kafka

Kafka is a high-throughput, log-based publish-subscribe messaging system.

It holds on to the messages after they have been consumed. This makes it well suited for failure recovery by replaying the recent commands or events. In the diagram above, the Command Queue and the Event Queue are implemented in Kafka queues.

### Amazon DynamoDB

DynamoDB is a flexible and scalable K/V store which we're using as the Event Store. It doesn't really matter where this goes as long as it's reliable and allows you to query a subset of the events by a given date range.

To take care of duplicates, we simply rewrite the same event into Dynamo using the event uuid as a key. This will overwrite the duplicate event, but won't change anything.

### Datomic

Datomic is a fully transactional, distributed Entity, Attribute, Value, Tx (EAVT) database. It provides expressive querying facilities, has a single-node transactor and near-arbitrary read scalability.

The single-node transactor is key to de-duplicating the event aggregation. We created an `:idempotent-tx` transactor function that checks:

 - If a tx is _already_ tagged with the given `:event/uuid`, convert tx to a no-op
 - If a tx is _not_ tagged, tag this tx with the `:event/uuid` and commit.

Another concern is that a duplicated command could produce a different set of events when one set of aggregates reaches the transactor before the other command gets to evaluate. 

To prevent inconsistent results, we tag the command with the current `basis-t`. This roots our command to a specific immutable database value.  The command should then always evaluate to the same result provided that it makes decisions based on the Datomic aggregate as of `basis-t`.

## Running cqrs-server

We've covered some of the larger design decisions, now let's delve into the cqrs-server specific implementation.

First, download and unzip dynamodb local from <http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Tools.DynamoDBLocal.html> and run:

```bash
java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar
```

Next, download Kafka from <http://kafka.apache.org/downloads.html>, unzip and run both:
```bash
bin/zookeeper-server-start.sh config/zookeeper.properties
bin/kafka-server-start.sh config/server.properties
```
Finally, clone cqrs-server and run:
```bash
git clone https://github.com/Yuppiechef/cqrs-server.git
cd cqrs-server
lein repl
```
After a bit of compiling you should have a REPL waiting, run the following function:
```clojure
(start)
```
It should give you a `"Setup Complete"` message once it's done and ready to start taking commands. Go take a look at the `src/cqrs_server/module.clj` file and have a look.

## Playing around
The `module.clj` should give you a feel for roughly how you wouldd add your own logic into the system. Define an aggregate schema for datomic, install the command schema's and implement the `cqrs/process-command` and `cqrs/aggregate-event` multimethods for each of your commands and events respectively.

`cqrs/aggregate-event` is optional. You will notice that there is not aggregation for the `:user/register-failed` event. This will still record the event in Dynamo, but have no read view.

_**Quick aside**: For convenience, we will be using the `=>` in the samples below to indicate the repl prompt and prefix the result of the calls with `;;`_

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

The astute reader will notice that this discussion has primarily focussed on the Command part of CQRS and not so much on the Query part. This is because once you have your aggregate view, you're done.

Any part of your system that needs to read can consume the aggregate views directly with no need to interact with the cqrs-server.

# Conclusion

We have shown a functional distillation of CQRS. We've composed various pieces of software to build a solid foundation for a flexible distributed system.

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
  