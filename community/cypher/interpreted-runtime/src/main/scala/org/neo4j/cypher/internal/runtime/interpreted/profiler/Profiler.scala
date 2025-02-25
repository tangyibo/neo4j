/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted.profiler

import org.neo4j.common.Edition
import org.neo4j.cypher.internal.profiling.KernelStatisticProvider
import org.neo4j.cypher.internal.profiling.OperatorProfileEvent
import org.neo4j.cypher.internal.runtime.ClosingIterator
import org.neo4j.cypher.internal.runtime.ClosingLongIterator
import org.neo4j.cypher.internal.runtime.CypherRow
import org.neo4j.cypher.internal.runtime.NodeOperations
import org.neo4j.cypher.internal.runtime.Operations
import org.neo4j.cypher.internal.runtime.PrimitiveLongHelper
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.RelationshipIterator
import org.neo4j.cypher.internal.runtime.RelationshipOperations
import org.neo4j.cypher.internal.runtime.interpreted.DelegatingOperations
import org.neo4j.cypher.internal.runtime.interpreted.DelegatingQueryContext
import org.neo4j.cypher.internal.runtime.interpreted.pipes.PipeDecorator
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.cypher.internal.util.attribution.Id
import org.neo4j.cypher.result.OperatorProfile
import org.neo4j.internal.kernel.api.Cursor
import org.neo4j.internal.kernel.api.DefaultCloseListenable
import org.neo4j.internal.kernel.api.KernelReadTracer
import org.neo4j.internal.kernel.api.NodeCursor
import org.neo4j.internal.kernel.api.NodeValueIndexCursor
import org.neo4j.internal.kernel.api.PropertyCursor
import org.neo4j.internal.kernel.api.RelationshipScanCursor
import org.neo4j.internal.kernel.api.RelationshipTraversalCursor
import org.neo4j.internal.kernel.api.RelationshipValueIndexCursor
import org.neo4j.kernel.impl.factory.DbmsInfo
import org.neo4j.storageengine.api.RelationshipVisitor
import org.neo4j.values.storable.Value

class Profiler(dbmsInfo: DbmsInfo,
               stats: InterpretedProfileInformation) extends PipeDecorator {
  outerProfiler =>

  private var planIdStack: List[Id] = Nil
  private var lastObservedStats = PageCacheStats(0, 0)

  private def startAccountingPageCacheStatsFor(statisticProvider: KernelStatisticProvider, id: Id): Unit = {
    val currentStats = PageCacheStats(statisticProvider.getPageCacheHits, statisticProvider.getPageCacheMisses)

    planIdStack.headOption.foreach {
      previousId =>
        stats.pageCacheMap(previousId) += (currentStats - lastObservedStats)
    }

    planIdStack ::= id
    lastObservedStats = currentStats
  }

  private def stopAccountingPageCacheStatsFor(statisticProvider: KernelStatisticProvider, id: Id): Unit = {
    val head :: rest = planIdStack
    require(head == id,
            s"We messed up accounting the page cache statistics. Expected to pop $id but popped $head. Remaining stack: $planIdStack")

    val currentStats = PageCacheStats(statisticProvider.getPageCacheHits, statisticProvider.getPageCacheMisses)
    stats.pageCacheMap(id) += (currentStats - lastObservedStats)

    planIdStack = rest
    lastObservedStats = currentStats
  }

  private def updatePageCacheStatistics(pipeId: Id, f: (KernelStatisticProvider, Id) => Unit): Unit = {
    val context = stats.dbHitsMap(pipeId)
    val statisticProvider = context.transactionalContext.kernelStatisticProvider
    f(statisticProvider, pipeId)
  }


  override def decorate(planId: Id, iter: ClosingIterator[CypherRow]): ClosingIterator[CypherRow] = {
    val oldCount = stats.rowMap.get(planId).map(_.count).getOrElse(0L)

    val resultIter =
      new ProfilingIterator(
        iter,
        oldCount,
        planId,
        if (trackPageCacheStats) updatePageCacheStatistics(_, startAccountingPageCacheStatsFor) else _ => (),
        if (trackPageCacheStats) updatePageCacheStatistics(_, stopAccountingPageCacheStatsFor) else _ => ())

    stats.rowMap(planId) = resultIter
    resultIter
  }

  override def decorate(planId: Id, state: QueryState): QueryState = {
    stats.setMemoryTracker(state.memoryTracker)
    val decoratedContext = stats.dbHitsMap.getOrElseUpdate(planId, state.query match {
      case p: ProfilingPipeQueryContext => new ProfilingPipeQueryContext(p.inner)
      case _ => new ProfilingPipeQueryContext(state.query)
    })

    if (trackPageCacheStats) {
      startAccountingPageCacheStatsFor(decoratedContext.transactionalContext.kernelStatisticProvider, planId)
    }
    state.withQueryContext(decoratedContext)
  }

  override def afterCreateResults(planId: Id, state: QueryState): Unit = {
    if (trackPageCacheStats) {
      stopAccountingPageCacheStatsFor(state.query.transactionalContext.kernelStatisticProvider, planId)
    }
  }

  private def trackPageCacheStats = {
    dbmsInfo.edition != Edition.COMMUNITY
  }

  override def innerDecorator(outerPlanId: Id): PipeDecorator = new PipeDecorator {
    innerProfiler =>

    def innerDecorator(planId: Id): PipeDecorator = innerProfiler

    def decorate(planId: Id, state: QueryState): QueryState =
      outerProfiler.decorate(outerPlanId, state)

    def decorate(planId: Id, iter: ClosingIterator[CypherRow]): ClosingIterator[CypherRow] = iter

    override def afterCreateResults(planId: Id, state: QueryState): Unit = outerProfiler.afterCreateResults(outerPlanId, state)
  }
}

trait Counter {
  protected var _count = 0L
  def count: Long = _count

  def increment(): Unit = {
    if (count != OperatorProfile.NO_DATA)
      _count += 1L
  }

  def increment(hits: Long): Unit = {
    if (count != OperatorProfile.NO_DATA)
      _count += hits
  }

  def invalidate(): Unit = {
    _count = OperatorProfile.NO_DATA
  }
}

final class ProfilingPipeQueryContext(inner: QueryContext)
  extends DelegatingQueryContext(inner) with Counter {
  self =>

  override protected def singleDbHit[A](value: A): A = {
    increment()
    value
  }

  override protected def unknownDbHits[A](value: A): A = {
    invalidate()
    value
  }

  override protected def manyDbHits[A](value: ClosingIterator[A]): ClosingIterator[A] = {
    increment()
    value.map {
      v =>
        increment()
        v
    }
  }

  override protected def manyDbHits(value: ClosingLongIterator): ClosingLongIterator = {
    increment()
    PrimitiveLongHelper.mapPrimitive(value, { x =>
      increment()
      x
    })
  }

  override protected def manyDbHits(inner: RelationshipIterator): RelationshipIterator = new RelationshipIterator {
    increment()
    override def relationshipVisit[EXCEPTION <: Exception](relationshipId: Long, visitor: RelationshipVisitor[EXCEPTION]): Boolean =
      inner.relationshipVisit(relationshipId, visitor)

    override def next(): Long = {
      increment()
      inner.next()
    }

    override def hasNext: Boolean = inner.hasNext

    override def startNodeId(): Long = inner.startNodeId()

    override def endNodeId(): Long = inner.endNodeId()

    override def typeId(): Int = inner.typeId()
  }

  override protected def manyDbHitsCliRi(inner: ClosingLongIterator with RelationshipIterator): ClosingLongIterator with RelationshipIterator =
    new ClosingLongIterator with RelationshipIterator {
      increment()
      override def relationshipVisit[EXCEPTION <: Exception](relationshipId: Long, visitor: RelationshipVisitor[EXCEPTION]): Boolean =
        inner.relationshipVisit(relationshipId, visitor)

      override def next(): Long = {
        increment()
        inner.next()
      }

      override def startNodeId(): Long = inner.startNodeId()

      override def endNodeId(): Long = inner.endNodeId()

      override def typeId(): Int = inner.typeId()

      override def close(): Unit = inner.close()

      override protected[this] def innerHasNext: Boolean = inner.hasNext
    }

  override protected def manyDbHits(nodeCursor: NodeCursor): NodeCursor = {

    val tracer = new PipeTracer
    nodeCursor.setTracer(tracer)
    nodeCursor
  }

  override protected def manyDbHits(propertyCursor: PropertyCursor): PropertyCursor = {
    val tracer = new PipeTracer
    propertyCursor.setTracer(tracer)
    propertyCursor
  }

  override protected def manyDbHits(relationshipScanCursor: RelationshipScanCursor): RelationshipScanCursor = {
    val tracer = new PipeTracer
    relationshipScanCursor.setTracer(tracer)
    relationshipScanCursor
  }

  override protected def manyDbHits(inner: NodeValueIndexCursor): NodeValueIndexCursor =  new ProfilingCursor(inner) with NodeValueIndexCursor {

    override def numberOfProperties(): Int = inner.numberOfProperties()

    override def hasValue: Boolean = inner.hasValue

    override def propertyValue(offset: Int): Value = inner.propertyValue(offset)

    override def node(cursor: NodeCursor): Unit = inner.node(cursor)

    override def nodeReference(): Long = inner.nodeReference()

    override def score(): Float = inner.score()
  }

  override protected def manyDbHits(inner: RelationshipValueIndexCursor): RelationshipValueIndexCursor =  new ProfilingCursor(inner) with RelationshipValueIndexCursor {
    override def numberOfProperties(): Int = inner.numberOfProperties()
    override def hasValue: Boolean = inner.hasValue
    override def propertyValue(offset: Int): Value = inner.propertyValue(offset)
    override def relationship(cursor: RelationshipScanCursor): Unit = inner.relationship(cursor)
    override def sourceNode(cursor: NodeCursor): Unit = inner.sourceNode(cursor)
    override def targetNode(cursor: NodeCursor): Unit = inner.targetNode(cursor)
    override def `type`(): Int = inner.`type`()
    override def sourceNodeReference(): Long = inner.sourceNodeReference()
    override def targetNodeReference(): Long = inner.targetNodeReference()
    override def relationshipReference(): Long = inner.relationshipReference()
    override def score(): Float = inner.score()
  }

  override protected def manyDbHits(inner: RelationshipTraversalCursor): RelationshipTraversalCursor = {
    val tracer = new PipeTracer
    inner.setTracer(tracer)
    inner
  }

  override protected def manyDbHits(count: Int): Int = {
    increment(count)
    count
  }

  abstract class ProfilingCursor(inner: Cursor) extends DefaultCloseListenable with Cursor {
    override def next(): Boolean = {
      increment()
      inner.next()
    }

    override def closeInternal(): Unit = inner.close()

    override def isClosed: Boolean = inner.isClosed

    override def setTracer(tracer: KernelReadTracer): Unit = inner.setTracer(tracer)

    override def removeTracer(): Unit = inner.removeTracer()
  }

  class PipeTracer() extends OperatorProfileEvent {

    override def dbHit(): Unit = {
      increment()
    }

    override def dbHits(hits: Long): Unit = {
      increment(hits)
    }

    override def row(): Unit = {}

    override def row(hasRow: Boolean): Unit = {}

    override def rows(n: Long): Unit = {}

    override def close(): Unit = {
    }
  }

  class ProfilerOperations[T, CURSOR](inner: Operations[T, CURSOR]) extends DelegatingOperations[T, CURSOR](inner) {
    override protected def singleDbHit[A](value: A): A = self.singleDbHit(value)
    override protected def manyDbHits[A](value: ClosingIterator[A]): ClosingIterator[A] = self.manyDbHits(value)

    override protected def manyDbHits[A](value: ClosingLongIterator): ClosingLongIterator = self.manyDbHits(value)
  }

  override val nodeOps: NodeOperations = new ProfilerOperations(inner.nodeOps) with NodeOperations
  override val relationshipOps: RelationshipOperations = new ProfilerOperations(inner.relationshipOps) with RelationshipOperations
}

class ProfilingIterator(inner: ClosingIterator[CypherRow],
                        startValue: Long,
                        pipeId: Id,
                        startAccouting: Id => Unit,
                        stopAccounting: Id => Unit) extends ClosingIterator[CypherRow]
  with Counter {

  _count = startValue

  override protected[this] def closeMore(): Unit = inner.close()

  def innerHasNext: Boolean = {
    startAccouting(pipeId)
    val hasNext = inner.hasNext
    stopAccounting(pipeId)
    hasNext
  }

  def next(): CypherRow = {
    increment()
    startAccouting(pipeId)
    val result = inner.next()
    stopAccounting(pipeId)
    result
  }
}
