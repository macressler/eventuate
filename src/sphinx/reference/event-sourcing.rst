.. _ref-event-sourced-actors:

Event-sourced actors
--------------------

An introduction to event-sourced actors is already given in section :ref:`architecture` and the :ref:`user-guide`. Applications use event-sourced actors for writing events to an event log and for maintaining in-memory write models on the command side (C) of a CQRS_ application. Event-sourced actors distinguish command processing from event processing. They must extend the EventsourcedActor_ trait and implement a :ref:`command-handler` and an :ref:`event-handler`.

.. _command-handler:

Command handler
~~~~~~~~~~~~~~~

A command handler is partial function of type ``PartialFunction[Any, Unit]`` for which a type alias ``Receive`` exists. It can be defined by implementing ``onCommand``:

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: command-handler

Messages sent by an application to an event-sourced actor are received by its command handler. Usually, a command handler first validates a command, then derives one or more events from it, persists these events with ``persist`` and replies with the persistence result. The ``persist`` method has the following signature\ [#]_:

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: persist-signature

The ``persist`` method can be called one ore more times per received command. Calling ``persist`` does not immediately write events to the event log. Instead, events from ``persist`` calls are collected in memory and written to the event log when ``onCommand`` returns. 

Events are written asynchronously to the event-sourced actor’s ``eventLog``. After writing, the ``eventLog`` actor internally replies to the event-sourced actor with a success or failure message which is passed as argument to the persist ``handler``. Before calling the persist ``handler``, the event-sourced actor internally calls the ``onEvent`` handler with the written event if writing was successful and ``onEvent`` is defined at that event.

Both, event handler and persist handler are called on a dispatcher thread of the actor. They can therefore safely access internal actor state. The ``sender()`` reference of the original command sender is also preserved, so that a persist handler can reply to the initial command sender.

.. hint::
   The ``EventsourcedActor`` trait also defines a ``persistN`` method. Refer to the EventsourcedActor_ API documentation for details.

.. note::
   A command handler should not modify persistent actor state i.e. state that is derived from events. 

Handling persistence failures
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Persistence may fail for several reasons. For example, event serialization or writing to the storage backend may fail, to mention only two examples. In general, a persist handler that is called with a ``Failure`` argument should not make any assumptions whether the corresponding event has been written or not. For example, an event could have been actually written to the storage backend but the ACK was lost which causes ``persist`` to complete with a failure.

One way to deal with this situation is to restart the event-sourced actor and inspect the recovered state whether that event has been processed or not. If it has been processed, the application can continue with the next command, otherwise it should re-send the failed command. This strategy avoids duplicates in the event log.

Duplicates are not an issue if state update operations executed by an event handler are idempotent. In this case, an application may simply re-send a failed command without restarting the event-sourced actor. 

State synchronization
~~~~~~~~~~~~~~~~~~~~~

As explained in section :ref:`command-handler`, events are persisted asynchronously. What happens if another command is sent to an event-sourced actor while persistence is in progress? This depends on the value of ``stateSync``, a member of ``EventsourcedActor`` that can be overridden.

.. includecode:: ../../main/scala/com/rbmhtechnology/eventuate/EventsourcedActor.scala
   :snippet: state-sync

If ``stateSync`` is ``true`` (default), new commands are stashed_ while persistence is in progress. Consequently, new commands see actor state that is *in sync* with the events in the event log. A consequence is limited write throughput, because :ref:`batching` of write requests is not possible in this case\ [#]_. This setting is recommended for event-sourced actors that must validate commands against current state.

If ``stateSync`` is ``false``, new commands are dispatched to ``onCommand`` immediately. Consequently, new commands may see stale actor state. The advantage is significantly higher write throughput as :ref:`batching` of write requests is possible. This setting is recommended for event-sourced actors that don’t need to validate commands against current state.

If a sender sends several (update) commands followed by a query to an event-sourced actor that has ``stateSync`` set to ``false``, the query will probably not see the state change from the preceding commands. To achieve read-your-write consistency, the command sender should wait for a reply from the last command before sending the query. The reply must of course be sent from within a ``persist`` handler.

.. note::
   State synchronization settings only apply to a single actor instance. Events that are emitted concurrently by other actors and handled by that instance can arrive at any time and modify actor state. Anyway, concurrent events are not relevant for achieving read-your-write consistency and should be handled as described in the :ref:`user-guide`.

.. _event-handler:

Event handler
~~~~~~~~~~~~~

An event handler is partial function of type ``PartialFunction[Any, Unit]`` for which a type alias ``Receive`` exists. It can be defined by implementing ``onEvent``. An event handler handles persisted events by updating actor state from event details. 

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: event-handler

Event metadata of the last handled event can be obtained with the ``last*`` methods defined by ``EventsourcedActor``. For example, ``lastSequenceNr`` returns the event’s local sequence number, ``lastVectorTimestamp`` returns the event’s vector timestamp. A complete reference is given by the EventsourcedActor_ API documentation.

.. note::
   An event handler should only update internal actor state without having further side-effects. An exception is :ref:`reliable-delivery` of messages and :ref:`guide-event-collaboration` with PersistOnEvent_.

Causality tracking
~~~~~~~~~~~~~~~~~~

As described in section :ref:`vector-clocks`, Eventuate’s causality tracking default can be formalized in `plausible clocks`_. To achieve more fine-grained causality tracking, event-sourced actors can reserve their own entry in a vector clock. To reserve its own entry, a concrete ``EventsourcedActor`` must override the ``sharedClockEntry`` method to return ``false``.

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: clock-entry-class

The value of ``sharedClockEntry`` may also be instance-specific, if required.

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: clock-entry-instance

.. _ref-event-sourced-views:

Event-sourced views
-------------------

An introduction to event-sourced views is already given in section :ref:`architecture` and the :ref:`user-guide`. Applications use event-sourced views for for maintaining in-memory read models on the query side (Q) of a CQRS_ application.

Like event-sourced actors, event-sourced views distinguish command processing from event processing. They must implement the EventsourcedView_ trait. ``EventsourcedView`` is a functional subset of ``EventsourcedActor`` that cannot ``persist`` events.

.. _ref-event-sourced-writers:

Event-sourced writers
---------------------

An introduction to event-sourced writers is already given in section :ref:`architecture`. Applications use event-sourced writers for maintaining persistent read models on the query side (Q) of a CQRS_ application.

Like event-sourced views, event-sourced writers can only consume events from an event log but can make incremental batch updates to external, application-defined query databases. A query database can be a relational database, a graph database or whatever is needed by an application. Concrete writers must implement the EventsourcedWriter_ trait.

This section outlines how to update a persistent read model in Cassandra_ from events consumed by an event-sourced writer. The relevant events are:

.. includecode:: ../../test/scala/com/rbmhtechnology/example/querydb/Emitter.scala
   :snippet: events

The persistent read model is a ``CUSTOMER`` table with the following structure::

     id | first  | last    | address
    ----+--------+---------+-------------
      1 | Martin | Krasser | Somewhere 1
      2 | Volker | Stampa  | Somewhere 3
      3 | ...    | ...     | ...

The read model update progress is written to a separate ``PROGRESS`` table with a single ``sequence_nr`` column::

     id | sequence_nr
    ----+-------------
      0 |           3

The stored sequence number is that of the last successfully processed event. An event is considered as successfully processed if its data have been written to the ``CUSTOMER`` table. Only a single row is needed in the ``PROGRESS`` table to track the update progress for the whole ``CUSTOMER`` table.

The event-sourced ``Writer`` in the following example implements ``EventsourcedWriter[Long, Unit]`` (where ``Long`` is the type of the initial read result and ``Unit`` the type of write results). It is initialized with an ``eventLog`` from which it consumes events and a Cassandra ``Session`` for writing event processing results.

.. includecode:: ../../test/scala/com/rbmhtechnology/example/querydb/Writer.scala
   :snippet: writer

.. hint::
   The full example source code is available `here <https://github.com/RBMHTechnology/eventuate/tree/master/src/test/scala/com/rbmhtechnology/example/querydb>`_.

On a high level, the example ``Writer`` implements the following behavior:

- During initialization (after start or restart) it asynchronously ``read``\ s the stored update progress from the ``PROGRESS`` table. The read result is passed as argument to ``readSuccess`` and incremented by ``1`` before returning it to the caller. This causes the ``Writer`` to resume event processing from that position in the event log.
- Event are processed in ``onEvent`` by translating them to Cassandra update statements which are added to an in-memory ``batch`` of type ``Vector[BoundStatement]``. The batch is written to Cassandra when Eventuate calls the ``write`` method.
- The ``write`` method asynchronously updates the ``CUSTOMER`` table with the statements contained in ``batch`` and then updates the ``PROGRESS`` table with the sequence number of the last processed event. After having submitted the statements to Cassandra, the batch is cleared for further event processing. Event processing can run concurrently to write operations. 
- A ``batch`` that has been updated while a write operation is in progress is written directly after the current write operation successfully completes. If no write operation is in progress, a change to ``batch`` is written immediately. This keeps read model update delays at a minimum and increases batch sizes under increasing load. Batch sizes can be limited with ``replayBatchSize``.

If a ``write`` (or ``read``) operation fails, the writer is restarted, by default, and resumes event processing from the last stored sequence number + ``1``. This behavior can be changed by overriding ``writeFailure`` (or ``readFailure``) from ``EventsourcedWriter``.

.. note::
   The example does not use Cassandra ``BatchStatement``\ s for reasons explained in `this article <https://medium.com/@foundev/cassandra-batch-loading-without-the-batch-keyword-40f00e35e23e>`_. Atomic writes are not needed because database updates in this example are idempotent and can be re-tried in failure cases. Failure cases where idempotency is relevant are partial updates to the ``CUSTOMER`` table or a failed write to the ``PROGRESS`` table. ``BatchStatement``\ s should only be used when database updates are not idempotent and atomicity is required on database level.
   
.. _stateful-writers:

Stateful writers
~~~~~~~~~~~~~~~~

The above ``Writer`` implements a stateless writer. Although it accumulates batches while a write operation is in progress, it cannot recover permanent in-memory state from the event log, because event processing only starts from the last stored sequence number. If a writer needs to be stateful, it must return ``None`` from ``readSuccess``. In this case, event replay either starts from scratch or from a previously stored snapshot. A stateful writer should still write the update progress to the ``PROGRESS`` table but exclude events with a sequence number less than or equal to the stored sequence number from contributing to the update ``batch``.

.. _ref-event-sourced-processors:

Event-sourced processors
------------------------

An introduction to event-sourced processors is already given in section :ref:`architecture`. Applications use event-sourced processors to consume events form a source event log, process these events and write the processed events to a target event log. With processors, event logs can be connected to event stream processing pipelines and graphs.

Event-sourced processors are a specialization of :ref:`event-sourced-writers` where the *external database* is a target event log. Concrete stateless processors must implement the EventsourcedProcessor_ trait, stateful processors the StatefulProcessor_ trait (see also :ref:`stateful-writers`).

The following example ``Processor`` is an implementation of ``EventsourcedProcessor``. In addition to providing a source ``eventLog``, a concrete processor must also provide a ``targetEventLog``:

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: processor

The event handler implemented by a processor is ``processEvent``. The type of the handler is defined as:

.. includecode:: ../../main/scala/com/rbmhtechnology/eventuate/EventsourcedProcessor.scala
   :snippet: process

Processed events, to be written to the target event log, are returned by the handler as ``Seq[Any]``. With this handler signature, events from the source log can be 

- excluded from being written to the target log by returning an empty ``Seq`` 
- transformed one-to-one by returning a ``Seq`` of size 1 or even
- transformed and split by returning a ``Seq`` of size greater than ``1``

.. note::
   ``EventsourcedProcessor`` and ``StatefulProcessor`` internally ensure that writing to the target event log is idempotent. Applications don’t need to take extra care about idempotency.

State recovery
--------------

When an event-sourced actor or view is started or re-started, events are replayed to its ``onEvent`` handler so that internal state can be recovered\ [#]_. This is also the case for stateful event-sourced writers and processors. Event replay is initiated internally by sending a ``Replay`` message to the ``eventLog`` actor:

.. includecode:: ../../main/scala/com/rbmhtechnology/eventuate/EventsourcedView.scala
   :snippet: replay

The ``replay`` method is defined by EventsourcedView_ and automatically called when an ``EventsourcedView`` or ``EventsourcedActor`` is started or re-started.

Sending a ``Replay`` message automatically registers the sending actor at its event log, so that newly written events can be immediately routed to that actor. If the actor is stopped it is automatically de-registered.

While an event-sourced actor, view, writer or processor is recovering i.e. replaying messages, its ``recovering`` method returns ``true``. If recovery successfully completes, its empty ``onRecovered()`` method is called which can be overridden by applications.

During recovery, new commands are stashed_ and dispatched to ``onCommand`` after recovery successfully completed. This ensures that new commands never see partially recovered state.

Backpressure
~~~~~~~~~~~~

If event handling is slower than event replay, events are buffered in the mailboxes of event-sourced actors, views, writers and processors. In order to avoid out-of-memory errors, Eventuate has a built-in backpressure mechanism for event replay.

After a configurable number of events, replay is suspended for giving event handlers time to catch up. When they are done, replay is automatically resumed. The default number of events to be replayed before replay is suspended can be configured with:

.. includecode:: ../conf/common.conf
   :snippet: replay-batch-size

Concrete event-sourced actors, views, writers and processors can override the configured default value by overriding ``replayBatchSize``:

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: replay-batch-size

.. _snapshots:

Snapshots
---------

Recovery times increase with the number of events that are replayed to event-sourced actors, views, stateful writers or stateful processors. They can be decreased by starting event replay from a previously saved snapshot of internal state rather than replaying events from scratch. Event-sourced actors, views, stateful writers and stateful processors can save snapshots by calling ``save`` within their command handler:

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: snapshot-save

Snapshots are saved asynchronously. On completion, a user-defined handler of type ``Try[SnapshotMetadata] => Unit`` is called. Like a ``persist`` handler, a ``save`` handler may also close over actor state and can reply to the command sender using the ``sender()`` reference. 

An event-sourced actor that is :ref:`tracking-conflicting-versions` of application state can also save ``ConcurrentVersions[A, B]`` instances directly. One can even configure custom serializers for type parameter ``A`` as explained in section :ref:`snapshot-serialization`.

During recovery, the latest snapshot saved by an event-sourced actor, view, stateful writer or stateful processor is loaded and can be handled with the ``onSnapshot`` handler. This handler should initialize internal actor state from the loaded snapshot: 

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: snapshot-load

If ``onSnapshot`` is not defined at the loaded snapshot or not overridden at all, event replay starts from scratch. If ``onSnapshot`` is defined at the loaded snapshot, only events that are not covered by that snapshot will be replayed. 

Event-sourced actors that implement ``ConfirmedDelivery`` for :ref:`reliable-delivery` automatically include unconfirmed messages into state snapshots. These are restored on recovery and re-delivered on recovery completion.

.. note::
   State objects passed as argument to ``save`` should be *immutable objects*. If this is not the case, the caller is responsible for creating a defensive copy before passing it as argument to ``save``.

Storage locations
~~~~~~~~~~~~~~~~~

Snapshots are currently stored in a directory that can be configured with

.. includecode:: ../conf/snapshot.conf
   :snippet: snapshot-dir

in ``application.conf``. The maximum number of stored snapshots per event-sourced actor, view, writer or processor can be configured with

.. includecode:: ../conf/snapshot.conf
   :snippet: snapshot-num

If this number is exceeded, older snapshots are automatically deleted.

.. _event-routing:


Event routing
-------------

An event that is emitted by an event-sourced actor or processor can be routed to other event-sourced actors, views, writers and processors if they share an :ref:`event-log`\ [#]_ . The default event routing rules are:

- If an event-sourced actor, view, writer or processor has an undefined ``aggregateId``, all events are routed to it. It may choose to handle only a subset of them though.
- If an event-sourced actor, view, writer or processor has a defined ``aggregateId``, only events emitted by event-sourced actors or processors with the same ``aggregateId`` are routed to it.

Routing destinations are defined during emission of an event and are persisted together with the event\ [#]_. This makes routing decisions repeatable during event replay and allows for routing rule changes without affecting past routing decisions. Applications can define additional routing destinations with the ``customDestinationAggregateIds`` parameter of ``persist``:

.. includecode:: ../code/EventRoutingDoc.scala
   :snippet: custom-routing

Here, ``ExampleEvent`` is routed to destinations with ``aggregateId``\ s ``Some(“a2”)`` and ``Some(“a3”)`` in addition to the default routing destinations with ``aggregateId``\s ``Some(“a1”)`` and ``None``.

.. _reliable-delivery:

Reliable delivery
-----------------

Reliable, event-based remote communication between event-sourced actors should be done via a :ref:`replicated-event-log`. For reliable communication with other services that cannot connect to a replicated event log, event-sourced actors should use the ConfirmedDelivery_ trait:

.. includecode:: ../code/ReliableDeliveryDoc.scala
   :snippet: reliable-delivery

``ConfirmedDelivery`` supports the reliable delivery of messages to destinations by enabling applications to re-deliver messages until delivery is confirmed by destinations. In the example above, the reliable delivery of a message is initiated by sending a ``DeliverCommand`` to ``ExampleActor``. 

The generated ``DeliverEvent`` calls ``deliver`` to deliver a ``ReliableMessage`` to ``destination``. The ``deliveryId`` is the correlation identifier for the delivery ``Confirmation``. The ``deliveryId`` can be any application-defined id. Here, the event’s sequence number is used which can be obtained with ``lastSequenceNumber``. 

The destination confirms the delivery of the message by sending a ``Confirmation`` reply to the event-sourced actor from which the actor generates a ``ConfirmationEvent``. When handling the event, message delivery can be confirmed by calling ``confirm`` with the ``deliveryId`` as argument.

When the actor is re-started, unconfirmed ``ReliableMessage``\ s are automatically re-delivered to their ``destination``\ s. The example actor additionally schedules ``redeliverUnconfirmed`` calls to periodically re-deliver unconfirmed messages. This is done within the actor’s command handler.

.. _ref-event-collaboration:

Event collaboration
-------------------

Event collaboration is covered in the :ref:`guide-event-collaboration` section of the :ref:`user-guide`.

.. _ref-conditional-requests:

Conditional requests
--------------------

Conditional requests are covered in the :ref:`conditional-requests` section of the :ref:`user-guide`.

.. _command-stashing:

Command stashing
----------------

``EventsourcedView`` and ``EventsourcedActor`` override ``stash()`` and ``unstashAll()`` of ``akka.actor.Stash`` so that application-specific subclasses can safely stash and unstash commands. Stashing of events is not allowed. Hence, ``stash()`` must only be used in a command handler, using it in an event handler will throw ``StashError``. On the other hand, ``unsatshAll()`` can be used anywhere i.e. in a command handler, persist handler or event handler. The following is a trivial usage example which calls ``stash()`` in the command handler and ``unstashAll()`` in the persist handler:

.. includecode:: ../code/EventSourcingDoc.scala
   :snippet: command-stash

The ``UserManager`` maintains a persistent ``users`` map. User can be added to the map by sending a ``CreateUser`` command and updated by sending and ``UpdateUser`` command. Should these commands arrive in wrong order i.e. ``UpdateUser`` before a corresponding ``CreateUser``, the ``UserManager`` stashes ``UpdateUser`` and unstashes it after having successfully processed another ``CreateUser`` command. 

In the above implementation, an ``UpdateUser`` command might be repeatedly stashed and unstashed if the corresponding ``CreateUser`` command is preceded by other unrelated ``CreateUser`` commands. Assuming that out-of-order user commands are rare, the performance impact is limited. Alternatively, one could record stashed user ids in transient actor state and conditionally call ``unstashAll()`` by checking that state.

Custom serialization
--------------------

.. _event-serialization:

Custom event serialization
~~~~~~~~~~~~~~~~~~~~~~~~~~

Custom serializers for application-defined events can be configured with Akka's `serialization extension`_. For example, an application that wants to use a custom ``MyDomainEventSerializer`` for events of type ``MyDomainEvent`` (both defined in package ``com.example``) should add the following configuration to ``application.conf``:

.. includecode:: ../conf/serializer.conf
   :snippet: custom-event-serializer

``MyDomainEventSerializer`` must extend Akka’s Serializer_ trait. Please refer to Akka’s `serialization extension`_ documentation for further details.

Eventuate stores application-defined events as ``payload`` of DurableEvent_\ s. ``DurableEvent`` itself is serialized with DurableEventSerializer_, a `Protocol Buffers`_ based serializer that delegates ``payload`` serialization to a custom serializer. If no custom serializer is configured, Akka’s default serializer is used.

.. _replication-filter-serialization:

Custom replication filter serialization
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

In the same way as for application-defined events, custom serializers for :ref:`replication-filters` can also be configured via Akka's `serialization extension`_. For example, an application that wants to use a custom ``MyReplicationFilterSerializer`` for replication filters of type ``MyReplicationFilter`` (both defined in package ``com.example``) should add the following configuration to ``application.conf``:

.. includecode:: ../conf/serializer.conf
   :snippet: custom-filter-serializer

Custom replication filter serialization also works if the custom filter is part of a composite filter that has been composed with ``and`` or ``or`` combinators (see ReplicationFilter_ API). If no custom filter serializer is configured, Akka’s default serializer is used.

.. _snapshot-serialization:

Custom snapshot serialization
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Applications can also configure custom serializers for snapshots in the same way as for application-defined events and replication filters (see sections :ref:`event-serialization` and :ref:`replication-filter-serialization`). 

Custom snapshot serialization also works for state managed with ``ConcurrentVersions[A, B]``. A custom serializer configured for type parameter ``A`` is used whenever a snapshot of type ``ConcurrentVersions[A, B]`` is saved (see also :ref:`tracking-conflicting-versions`).

Custom serializers can also be configured for the type parameter ``A`` of ``MVRegister[A]``, ``LWWRegister[A]`` and ``ORSet[A]``, :ref:`commutative-replicated-data-types` for which the corresponding CRDT service interfaces provide a ``save`` method for saving snapshots.

.. [#] The ``customDestinationAggregateIds`` parameter is described in section :ref:`event-routing`.
.. [#] Writes from different event-sourced actors that have ``stateSync`` set to ``true`` are still batched, but not the writes from a single event-sourced actor.
.. [#] Event replay can optionally start from :ref:`snapshots` of actor state.
.. [#] Event-sourced processors can additionally route events between event logs.
.. [#] The routing destinations of a DurableEvent_ can be obtained with its ``destinationAggregateIds`` method.

.. _CQRS: http://martinfowler.com/bliki/CQRS.html
.. _stashed: http://doc.akka.io/docs/akka/2.3.9/scala/actors.html#stash
.. _serialization extension: http://doc.akka.io/docs/akka/2.3.9/scala/serialization.html
.. _Serializer: http://doc.akka.io/api/akka/2.3.9/#akka.serialization.Serializer
.. _Protocol Buffers: https://developers.google.com/protocol-buffers/
.. _plausible clocks: https://github.com/RBMHTechnology/eventuate/issues/68
.. _Cassandra: http://cassandra.apache.org/

.. _ConfirmedDelivery: ../latest/api/index.html#com.rbmhtechnology.eventuate.ConfirmedDelivery
.. _DurableEvent: ../latest/api/index.html#com.rbmhtechnology.eventuate.DurableEvent
.. _DurableEventSerializer: ../latest/api/index.html#com.rbmhtechnology.eventuate.serializer.DurableEventSerializer
.. _EventsourcedActor: ../latest/api/index.html#com.rbmhtechnology.eventuate.EventsourcedActor
.. _EventsourcedView: ../latest/api/index.html#com.rbmhtechnology.eventuate.EventsourcedView
.. _EventsourcedWriter: ../latest/api/index.html#com.rbmhtechnology.eventuate.EventsourcedWriter
.. _EventsourcedProcessor: ../latest/api/index.html#com.rbmhtechnology.eventuate.EventsourcedProcessor
.. _StatefulProcessor: ../latest/api/index.html#com.rbmhtechnology.eventuate.StatefulProcessor
.. _ReplicationFilter: ../latest/api/index.html#com.rbmhtechnology.eventuate.ReplicationFilter
.. _PersistOnEvent: ../latest/api/index.html#com.rbmhtechnology.eventuate.PersistOnEvent
