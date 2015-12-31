/*
 * Copyright (C) 2015 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.eventuate.log

import java.io.Closeable

import akka.actor._
import akka.dispatch.MessageDispatcher
import akka.event.LoggingAdapter

import com.rbmhtechnology.eventuate._
import com.rbmhtechnology.eventuate.EventsourcingProtocol._
import com.rbmhtechnology.eventuate.ReplicationProtocol._
import com.rbmhtechnology.eventuate.snapshot.filesystem._

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.util._

/**
 * Event log settings to be implemented by storage providers.
 */
trait EventLogSettings {
  /**
   * Maximum number of events to store per partition. If a storage provider does not support
   * partitioned event storage it should return `Long.MaxValue`, otherwise the appropriate
   * partition size. Eventuate internally calculates the target partition for batch writes.
   * If an event batch doesn't fit into the current partition, it will be written to the next
   * partition, so that batch writes are always single-partition writes.
   */
  def partitionSize: Long

  /**
   * Maximum number of clock recovery retries.
   */
  def initRetryMax: Int

  /**
   * Delay between clock recovery retries.
   */
  def initRetryDelay: FiniteDuration

  /**
   * Delay between two tries to physically delete all requested events while keeping
   * those that are not yet replicated.
   */
  def deletionRetryDelay: FiniteDuration
}

/**
 * A clock that tracks the current sequence number and the version vector of an event log.
 * The version vector is the merge result of vector timestamps of all events that have been
 * written to that event log.
 */
case class EventLogClock(sequenceNr: Long = 0L, versionVector: VectorTime = VectorTime.Zero) {
  /**
   * Advances `sequenceNr` by given `delta`.
   */
  def advanceSequenceNr(delta: Long = 1L): EventLogClock =
    copy(sequenceNr = sequenceNr + delta)

  /**
   * Sets `sequenceNr` to the event's local sequence number and merges `versionVector` with
   * the event's vector timestamp.
   */
  def update(event: DurableEvent): EventLogClock =
    copy(sequenceNr = event.localSequenceNr, versionVector = versionVector.merge(event.vectorTimestamp))
}

/**
 * Result of an event batch-read operation.
 *
 * @param events Read event batch.
 * @param to Last read position in the event log.
 */
case class BatchReadResult(events: Seq[DurableEvent], to: Long) extends DurableEventBatch

/**
 * View of a [[EventsourcingProtocol.Delete Delete]] request.
 *
 * @param toSequenceNr A marker that indicates that all event with a smaller sequence nr are not replayed any more.
 * @param remoteLogIds A set of remote log ids that must have replicated events before they these events are allowed
 *                     to be physically deleted locally.
 */
case class DeletionMetadata(toSequenceNr: Long, remoteLogIds: Set[String])

/**
 * Indicates that a storage backend doesn't support physical deletion of events.
 */
private class PhysicalDeletionNotSupportedException extends UnsupportedOperationException

/**
 * Event log storage provider interface (SPI).
 *
 * @tparam A Event iterator factory parameter type (see [[eventIterator]] factory method).
 */
trait EventLogSPI[A] { this: Actor =>
  /**
   * Event log settings.
   */
  def settings: EventLogSettings

  /**
   * Asynchronously recovers the event log clock during initialization.
   */
  def recoverClock: Future[EventLogClock]

  /**
   * Called after successful event log clock recovery.
   */
  def recoverClockSuccess(clock: EventLogClock): Unit = ()

  /**
   * Called after failed event log clock recovery.
   */
  def recoverClockFailure(cause: Throwable): Unit = ()

  /**
   * Asynchronously reads all stored local replication progresses.
   *
   * @see [[GetReplicationProgresses]]
   */
  def readReplicationProgresses: Future[Map[String, Long]]

  /**
   * Asynchronously reads the replication progress for given source `logId`.
   *
   * @see [[GetReplicationProgress]]
   */
  def readReplicationProgress(logId: String): Future[Long]

  /**
   * Asynchronously writes the replication `progress` for given source `logId`.
   */
  def writeReplicationProgress(logId: String, progress: Long): Future[Unit]

  /**
   * Creates a event iterator using the given `parameters` object. This method is called on a thread that is
   * '''not''' a dispatcher thread of an implementing [[EventLog]] actor. It is therefore important that the
   * returned iterator does not close over current actor state. If the initialization of the iterator depends
   * on current actor state, it must be captured during `eventIteratorParameters` calls and returned by these
   * methods as parameter object of type `A`. This object is then passed as argument to this method and used
   * for asynchronous event iterator construction.
   */
  def eventIterator(parameters: A): Iterator[DurableEvent] with Closeable

  /**
   * Creates an event iterator parameter object that encodes the given parameters. The returned parameter
   * object is passed as argument to `eventIterator`.
   *
   * @param fromSequenceNr sequence number to start iteration (inclusive).
   * @param toSequenceNr sequence number to stop iteration (inclusive).
   */
  def eventIteratorParameters(fromSequenceNr: Long, toSequenceNr: Long): A

  /**
   * Creates an event iterator parameter object that encodes the given parameters. The returned parameter
   * object is passed as argument to `eventIterator`.
   *
   * @param fromSequenceNr sequence number to start iteration (inclusive).
   * @param toSequenceNr sequence number to stop iteration (inclusive).
   * @param aggregateId must be contained in [[DurableEvent.destinationAggregateIds]].
   */
  def eventIteratorParameters(fromSequenceNr: Long, toSequenceNr: Long, aggregateId: String): A

  /**
   * Asynchronously batch-reads events from the raw event log. At most `max` events must be returned that are
   * within the sequence number bounds `fromSequenceNr` and `toSequenceNr` and that pass the given `filter`.
   *
   * @param fromSequenceNr sequence number to start reading (inclusive).
   * @param toSequenceNr sequence number to stop reading (inclusive)
   *                     or earlier if `max` events have already been read.
   */
  def read(fromSequenceNr: Long, toSequenceNr: Long, max: Int, filter: DurableEvent => Boolean): Future[BatchReadResult]

  /**
   * Synchronously writes `events` to the given `partition`. The partition is calculated from the configured
   * `partitionSizeMax` and the current sequence number. Asynchronous writes will be supported in future versions.
   *
   * @see [[EventLogSettings]]
   */
  def write(events: Seq[DurableEvent], partition: Long, clock: EventLogClock): Unit

  /**
   * Return the current [[DeletionMetadata]]
   */
  def readDeletionMetadata: Future[DeletionMetadata]

  /**
   * Synchronously writes metadata for a [[EventsourcingProtocol.Delete Delete]] request. This marks events up to
   * [[DeletionMetadata.toSequenceNr]] as deleted, i.e. they are not read on replay and indicates which remote logs
   * must have replicated these events before they are allowed to be physically deleted locally.
   */
  def writeDeletionMetadata(data: DeletionMetadata): Unit

  /**
   * Asynchronously and physically deletes events up to `toSequenceNr`. This operation completes when
   * physical deletion completed. A backend that does not support physical deletion should not override
   * this method.
   */
  def delete(toSequenceNr: Long): Future[Unit] = Future.failed(new PhysicalDeletionNotSupportedException)
}

/**
 * An abstract event log that handles [[EventsourcingProtocol]] and [[ReplicationProtocol]] messages and
 * translates them to read and write operations declared on the [[EventLogSPI]] trait. Storage providers
 * implement an event log by implementing the [[EventLogSPI]] methods.
 */
abstract class EventLog[A](id: String) extends Actor with EventLogSPI[A] with Stash with ActorLogging {
  import NotificationChannel._
  import EventLog._

  // ---------------------------------------------------------------------------
  //  TODO: only transfer version vector deltas to update replicaVersionVectors
  // ---------------------------------------------------------------------------

  /**
   * Service context for asynchronous read operations.
   */
  object services {
    /**
     * Dispatcher for asynchronous read operations.
     */
    implicit val readDispatcher: MessageDispatcher =
      context.system.dispatchers.lookup("eventuate.log.dispatchers.read-dispatcher")

    /**
     * Scheduler of current actor system.
     */
    implicit val scheduler: Scheduler =
      context.system.scheduler
  }

  /**
   * The clock that tracks the sequence number and version vector of this event log. The sequence
   * number is the log's logical time. The version vector is the merge result of vector timestamps
   * of all events that have been written to this event log. The version vector is used to exclude
   * events from being written if they are in the event log's causal past (which makes replication
   * writes idempotent).
   */
  private var clock: EventLogClock =
    EventLogClock()

  /**
   * Current [[DeletionMetadata]]
   */
  private var deletionMetadata = DeletionMetadata(0, Set.empty)

  /**
   * A flag indicating if a physical deletion process is currently running in the background
   */
  private var physicalDeletionRunning = false

  /**
   * An cache for the remote replication progress.
   * The remote replication progress is the sequence nr in the local log up to which
   * a remote log has replicated events. Events with a sequence number less than or equal
   * the corresponding replication progress are allowed to be physically deleted locally.
   */
  private var remoteReplicationProgress: Map[String, Long] = Map.empty

  /**
   * Cached version vectors of event log replicas. They are used to exclude redundantly read events from
   * being transferred to a replication target. This is an optimization to save network bandwidth. Even
   * without this optimization, redundantly transferred events are reliably excluded at the target site,
   * using its local version version vector. The version vector cache is continuously updated during event
   * replication.
   */
  private var replicaVersionVectors: Map[String, VectorTime] =
    Map.empty[String, VectorTime].withDefaultValue(VectorTime.Zero)

  /**
   * Registry for event-sourced actors, views, writers and processors interacting with this event
   * log.
   */
  private var registry: SubscriberRegistry =
    SubscriberRegistry()

  /**
   * Optional channel to notify [[Replicator]]s, reading from this event log, about updates.
   */
  private val channel: Option[ActorRef] =
    if (context.system.settings.config.getBoolean("eventuate.log.replication.update-notifications"))
      Some(context.actorOf(Props(new NotificationChannel(id))))
    else
      None

  /**
   * This event log's snapshot store.
   */
  private val snapshotStore: FilesystemSnapshotStore =
    new FilesystemSnapshotStore(new FilesystemSnapshotStoreSettings(context.system), id)

  /**
   * This event log's logging adapter.
   */
  private val logger: LoggingAdapter =
    log

  private def initializing: Receive = {
    case RecoverySuccess(state) =>
      clock = state.clock
      deletionMetadata = state.deleteMetadata
      if (deletionMetadata.toSequenceNr > 0) self ! PhysicalDelete
      recoverClockSuccess(clock)
      unstashAll()
      context.become(initialized)
    case RecoveryFailure(e) =>
      logger.error(e, "Cannot recover log state")
      context.stop(self)
    case other =>
      stash()
  }

  private def initialized: Receive = {
    case GetEventLogClock =>
      sender() ! GetEventLogClockSuccess(clock)
    case GetReplicationProgresses =>
      import services.readDispatcher
      val sdr = sender()
      readReplicationProgresses onComplete {
        case Success(p) => sdr ! GetReplicationProgressesSuccess(p)
        case Failure(e) => sdr ! GetReplicationProgressesFailure(e)
      }
    case GetReplicationProgress(sourceLogId) =>
      import services.readDispatcher
      val sdr = sender()
      val tvv = clock.versionVector
      readReplicationProgress(sourceLogId) onComplete {
        case Success(p) => sdr ! GetReplicationProgressSuccess(sourceLogId, p, tvv)
        case Failure(e) => sdr ! GetReplicationProgressFailure(e)
      }
    case SetReplicationProgress(sourceLogId, progress) =>
      val sdr = sender()
      implicit val dispatcher = context.dispatcher
      writeReplicationProgress(sourceLogId, progress) onComplete {
        case Success(_) => sdr ! SetReplicationProgressSuccess(sourceLogId, progress)
        case Failure(e) => sdr ! SetReplicationProgressFailure(e)
      }
    case Replay(fromSequenceNr, max, replyTo, Some(emitterAggregateId), iid) =>
      val adjustedFromSequenceNr = adjustFromSequenceNr(fromSequenceNr)
      val iteratorSettings = eventIteratorParameters(adjustedFromSequenceNr, clock.sequenceNr, emitterAggregateId) // avoid async evaluation
      registry = registry.registerAggregateSubscriber(context.watch(replyTo), emitterAggregateId)
      replayer(replyTo, eventIterator(iteratorSettings), adjustedFromSequenceNr) ! ReplayNext(max, iid)
    case Replay(fromSequenceNr, max, replyTo, None, iid) =>
      val adjustedFromSequenceNr = adjustFromSequenceNr(fromSequenceNr)
      val iteratorSettings = eventIteratorParameters(adjustedFromSequenceNr, clock.sequenceNr) // avoid async evaluation
      registry = registry.registerDefaultSubscriber(context.watch(replyTo))
      replayer(replyTo, eventIterator(iteratorSettings), adjustedFromSequenceNr) ! ReplayNext(max, iid)
    case r @ ReplicationRead(from, max, filter, targetLogId, _, currentTargetVersionVector) =>
      import services.readDispatcher
      val sdr = sender()
      channel.foreach(_ ! r)
      remoteReplicationProgress += targetLogId -> (0L max from - 1)
      read(from, clock.sequenceNr, max, evt => evt.replicable(currentTargetVersionVector, filter)) onComplete {
        case Success(r) => self.tell(ReplicationReadSuccess(r.events, r.to, targetLogId, null), sdr)
        case Failure(e) => self.tell(ReplicationReadFailure(e.getMessage, targetLogId), sdr)
      }
    case r @ ReplicationReadSuccess(events, _, targetLogId, _) =>
      // Post-exclude events using a possibly updated version vector received from the
      // target. This is an optimization to save network bandwidth. If omitted, events
      // are still excluded at target based on the current local version vector at the
      // target (for correctness).
      val currentTargetVersionVector = replicaVersionVectors(targetLogId)
      val updated = events.filterNot(_.before(currentTargetVersionVector))
      val reply = r.copy(updated, currentSourceVersionVector = clock.versionVector)
      sender() ! reply
      channel.foreach(_ ! reply)
      logFilterStatistics("source", events, updated)
    case r @ ReplicationReadFailure(_, _) =>
      sender() ! r
      channel.foreach(_ ! r)
    case w: Write =>
      processWrites(Seq(w))
    case WriteN(writes) =>
      processWrites(writes)
      sender() ! WriteNComplete
    case w: ReplicationWrite =>
      processReplicationWrites(Seq(w.copy(replyTo = sender())))
    case ReplicationWriteN(writes) =>
      processReplicationWrites(writes)
      sender() ! ReplicationWriteNComplete
    case Delete(toSequenceNr, remoteLogIds: Set[String]) =>
      Try {
        val actualDeletedToSeqNr = (toSequenceNr min clock.sequenceNr) max deletionMetadata.toSequenceNr
        if (actualDeletedToSeqNr > deletionMetadata.toSequenceNr) {
          val updatedDeletionMetadata = DeletionMetadata(actualDeletedToSeqNr, remoteLogIds)
          writeDeletionMetadata(updatedDeletionMetadata)
          deletionMetadata = updatedDeletionMetadata
          self ! PhysicalDelete
        }
      } match {
        case Success(_)  => sender() ! DeleteSuccess(deletionMetadata.toSequenceNr)
        case Failure(ex) => sender() ! DeleteFailure(ex)
      }
    case PhysicalDelete =>
      import context.dispatcher
      if (!physicalDeletionRunning) {
        // Becomes Long.MaxValue in case of an empty-set to indicate that all event are replicated as required
        val replicatedSeqNr = (deletionMetadata.remoteLogIds.map(remoteReplicationProgress.getOrElse(_, 0L)) + Long.MaxValue).min
        val deleteTo = deletionMetadata.toSequenceNr min replicatedSeqNr
        physicalDeletionRunning = true
        delete(deleteTo).onComplete {
          case Success(_)  => self ! PhysicalDeleteSuccess(deleteTo)
          case Failure(ex) => self ! PhysicalDeleteFailure(ex)
        }
      }
    case PhysicalDeleteSuccess(deletedTo) =>
      import services._
      physicalDeletionRunning = false
      if (deletionMetadata.toSequenceNr > deletedTo)
        scheduler.scheduleOnce(settings.deletionRetryDelay, self, PhysicalDelete)
    case PhysicalDeleteFailure(cause: PhysicalDeletionNotSupportedException) =>
    case PhysicalDeleteFailure(cause) =>
      import services._
      log.error(cause, "Physical deletion of events failed. Retry in {}", settings.deletionRetryDelay)
      physicalDeletionRunning = false
      scheduler.scheduleOnce(settings.deletionRetryDelay, self, PhysicalDelete)
    case LoadSnapshot(emitterId, replyTo, iid) =>
      import services.readDispatcher
      snapshotStore.loadAsync(emitterId) onComplete {
        case Success(s) => replyTo ! LoadSnapshotSuccess(s, iid)
        case Failure(e) => replyTo ! LoadSnapshotFailure(e, iid)
      }
    case SaveSnapshot(snapshot, initiator, replyTo, iid) =>
      import context.dispatcher
      snapshotStore.saveAsync(snapshot) onComplete {
        case Success(_) => replyTo.tell(SaveSnapshotSuccess(snapshot.metadata, iid), initiator)
        case Failure(e) => replyTo.tell(SaveSnapshotFailure(snapshot.metadata, e, iid), initiator)
      }
    case DeleteSnapshots(lowerSequenceNr) =>
      import context.dispatcher
      val sdr = sender()
      snapshotStore.deleteAsync(lowerSequenceNr) onComplete {
        case Success(_) => sdr ! DeleteSnapshotsSuccess
        case Failure(e) => sdr ! DeleteSnapshotsFailure(e)
      }
    case Terminated(replyTo) =>
      registry = registry.unregisterSubscriber(replyTo)
  }

  override def receive =
    initializing

  private[eventuate] def currentSystemTime: Long =
    System.currentTimeMillis

  private[eventuate] def adjustFromSequenceNr(seqNr: Long): Long = seqNr max (deletionMetadata.toSequenceNr + 1)

  private def replayer(destination: ActorRef, iterator: => Iterator[DurableEvent] with Closeable, fromSequenceNr: Long): ActorRef =
    context.actorOf(Props(new ChunkedEventReplay(destination, iterator)).withDispatcher(services.readDispatcher.id))

  private def processWrites(writes: Seq[Write]): Unit = {
    writeBatches(writes, prepareEvents(_, _, currentSystemTime)) match {
      case Success((updatedWrites, updatedEvents, clock2)) =>
        clock = clock2
        updatedWrites.foreach { w =>
          w.replyTo.tell(WriteSuccess(w.events, w.correlationId, w.instanceId), w.initiator)
          registry.notifySubscribers(w.events, _ != w.replyTo)
        }
        channel.foreach(_ ! Updated(updatedEvents))
      case Failure(e) =>
        writes.foreach(w => w.replyTo.tell(WriteFailure(w.events, e, w.correlationId, w.instanceId), w.initiator))
    }
  }

  private def processReplicationWrites(writes: Seq[ReplicationWrite]): Unit = {
    writes.foreach(w => replicaVersionVectors = replicaVersionVectors.updated(w.sourceLogId, w.currentSourceVersionVector))
    writeBatches(writes, prepareReplicatedEvents) match {
      case Success((updatedWrites, updatedEvents, clock2)) =>
        clock = clock2
        updatedWrites.foreach { w =>
          val ws = ReplicationWriteSuccess(w.size, w.sourceLogId, w.replicationProgress, clock2.versionVector)
          registry.notifySubscribers(w.events)
          channel.foreach(_ ! w)
          implicit val dispatcher = context.system.dispatchers.defaultGlobalDispatcher
          writeReplicationProgress(w.sourceLogId, w.replicationProgress) onComplete {
            case Success(_) =>
              w.replyTo ! ws
            case Failure(e) =>
              // Write failure of replication progress can be ignored. Using a stale
              // progress to resume replication will redundantly read events from a
              // source log but these events will be successfully identified as
              // duplicates, either at source or latest at target.
              logger.warning(s"Writing of replication progress failed: ${e.getMessage}")
              w.replyTo ! ReplicationWriteFailure(e)
          }
        }
        channel.foreach(_ ! Updated(updatedEvents))
      case Failure(e) =>
        writes.foreach { write =>
          write.replyTo ! ReplicationWriteFailure(e)
        }
    }
  }

  private def writeBatches[B <: UpdateableEventBatch[B]](writes: Seq[B], prepare: (Seq[DurableEvent], EventLogClock) => (Seq[DurableEvent], EventLogClock)): Try[(Seq[B], Seq[DurableEvent], EventLogClock)] =
    for {
      (partition, clock1) <- Try(adjustSequenceNr(writes.map(_.size).sum, settings.partitionSize, clock))
      (updatedWrites, clock2) = prepareBatches(writes, clock1, prepare)
      updatedEvents = updatedWrites.flatMap(_.events)
      _ <- Try(write(updatedEvents, partition, clock2))
    } yield (updatedWrites, updatedEvents, clock2)

  private def prepareBatches[B <: UpdateableEventBatch[B]](writes: Seq[B], clock: EventLogClock, prepare: (Seq[DurableEvent], EventLogClock) => (Seq[DurableEvent], EventLogClock)): (Seq[B], EventLogClock) =
    writes.foldLeft((Vector.empty[B], clock)) {
      case ((writes2, clock2), write) => prepare(write.events, clock2) match {
        case (updated, clock3) => (writes2 :+ write.update(updated), clock3)
      }
    }

  private def prepareEvents(events: Seq[DurableEvent], clock: EventLogClock, systemTimestamp: Long): (Seq[DurableEvent], EventLogClock) = {
    var snr = clock.sequenceNr
    var lvv = clock.versionVector

    val updated = events.map { e =>
      snr += 1L

      val e2 = e.prepare(id, snr, systemTimestamp)
      lvv = lvv.merge(e2.vectorTimestamp)
      e2
    }
    (updated, clock.copy(sequenceNr = snr, versionVector = lvv))
  }

  private def prepareReplicatedEvents(events: Seq[DurableEvent], clock: EventLogClock): (Seq[DurableEvent], EventLogClock) = {
    var snr = clock.sequenceNr
    var lvv = clock.versionVector

    val updated = events.foldLeft(Vector.empty[DurableEvent]) {
      case (acc, e) if e.before(clock.versionVector) =>
        // Exclude events from writing that are in the causal past of this event log. Excluding
        // them at the target is needed for correctness. Events are also excluded at sources
        // (to save network bandwidth) but this is only an optimization which cannot achieve
        // 100% filtering coverage for certain replication network topologies.
        acc
      case (acc, e) =>
        snr += 1L

        val e2 = e.prepare(id, snr, e.systemTimestamp)
        lvv = lvv.merge(e2.vectorTimestamp)
        acc :+ e2
    }
    logFilterStatistics("target", events, updated)
    (updated, clock.copy(sequenceNr = snr, versionVector = lvv))
  }

  private def logFilterStatistics(location: String, before: Seq[DurableEvent], after: Seq[DurableEvent]): Unit = {
    val bl = before.length
    val al = after.length
    if (al < bl) {
      val diff = bl - al
      val perc = diff * 100.0 / bl
      logger.info(f"[$id] excluded $diff events ($perc%3.1f%% at $location)")
    }
  }

  private def recoverState: Future[RecoveredState] = {
    import context.dispatcher
    for {
      clock <- recoverClock
      deleteMetadata <- readDeletionMetadata
    } yield RecoveredState(clock, deleteMetadata)
  }

  override def preStart(): Unit = {
    import services._
    Retry(recoverState, settings.initRetryDelay, settings.initRetryMax) onComplete {
      case Success(s) => self ! RecoverySuccess(s)
      case Failure(e) => self ! RecoveryFailure(e)
    }
  }
}

object EventLog {
  /**
   * State that is recovered from storage at startup
   */
  private case class RecoveredState(clock: EventLogClock, deleteMetadata: DeletionMetadata)

  /**
   * Internally sent to an [[EventLog]] after successful recovery of [[RecoveredState]].
   */
  private case class RecoverySuccess(state: RecoveredState)

  /**
   * Internally sent to an [[EventLog]] after failed recovery of [[RecoveredState]].
   */
  private case class RecoveryFailure(cause: Throwable)

  /**
   * Periodically sent to an [[EventLog]] after reception of a [[Delete]]-command to
   * instruct the log to physically delete logically deleted events that are alreday replicated.
   * @see DeletionMetadata
   */
  private case object PhysicalDelete

  /**
   * Internally sent to an [[EventLog]] after successful physical deletion
   */
  private case class PhysicalDeleteSuccess(deletedTo: Long)

  /**
   * Internally sent to an [[EventLog]] after failed physical deletion
   */
  private case class PhysicalDeleteFailure(ex: Throwable)

  /**
   * Partition number for given `sequenceNr`.
   */
  def partitionOf(sequenceNr: Long, partitionSizeMax: Long): Long =
    if (sequenceNr == 0L) -1L else (sequenceNr - 1L) / partitionSizeMax

  /**
   * Remaining partition size given the current `sequenceNr`.
   */
  def remainingPartitionSize(sequenceNr: Long, partitionSizeMax: Long): Long = {
    val m = sequenceNr % partitionSizeMax
    if (m == 0L) m else partitionSizeMax - m
  }

  /**
   * First sequence number of given `partition`.
   */
  def firstSequenceNr(partition: Long, partitionSizeMax: Long): Long =
    partition * partitionSizeMax + 1L

  /**
   * Last sequence number of given `partition`.
   */
  def lastSequenceNr(partition: Long, partitionSizeMax: Long): Long =
    (partition + 1L) * partitionSizeMax

  /**
   * Adjusts `clock.sequenceNumber` if a batch of `batchSize` doesn't fit in the current partition.
   */
  private def adjustSequenceNr(batchSize: Long, maxBatchSize: Long, clock: EventLogClock): (Long, EventLogClock) = {
    require(batchSize <= maxBatchSize, s"write batch size (${batchSize}) must not be greater than maximum partition size (${maxBatchSize})")

    val currentPartition = partitionOf(clock.sequenceNr, maxBatchSize)
    val remainingSize = remainingPartitionSize(clock.sequenceNr, maxBatchSize)
    if (remainingSize < batchSize) {
      (currentPartition + 1L, clock.advanceSequenceNr(remainingSize))
    } else {
      (currentPartition, clock)
    }
  }
}
