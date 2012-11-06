/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package table

import com.precog.common.{Path, VectorCase}
import com.precog.common.json._
import com.precog.bytecode.JType
import com.precog.yggdrasil.jdbm3._
import com.precog.yggdrasil.util._
import com.precog.util._

import com.precog.yggdrasil.table.cf.util.{Remap, Empty}

import TransSpecModule._

import blueeyes.bkka.AkkaTypeClasses
import blueeyes.json._
import blueeyes.json.JsonAST._
import org.apache.commons.collections.primitives.ArrayIntList
import org.joda.time.DateTime
import com.google.common.io.Files

import org.slf4j.Logger

import org.apache.jdbm.DBMaker
import java.io.File
import java.util.SortedMap

import com.precog.util.{BitSet, BitSetUtil, Loop}
import com.precog.util.BitSetUtil.Implicits._

import scala.collection.mutable
import scala.annotation.tailrec

import scalaz._
import scalaz.Ordering._
import scalaz.std.function._
import scalaz.std.list._
import scalaz.std.tuple._
//import scalaz.std.iterable._
import scalaz.std.option._
import scalaz.std.map._
import scalaz.std.set._
import scalaz.std.stream._
import scalaz.syntax.arrow._
import scalaz.syntax.monad._
import scalaz.syntax.monoid._
import scalaz.syntax.show._
import scalaz.syntax.traverse._
import scalaz.syntax.std.boolean._

import java.nio.CharBuffer

trait ColumnarTableTypes {
  type F1 = CF1
  type F2 = CF2
  type Scanner = CScanner
  type Reducer[α] = CReducer[α]
  type RowId = Int
}

trait ColumnarTableModuleConfig {
  def maxSliceSize: Int
  
  def maxSaneCrossSize: Long = 2400000000L    // 2.4 billion
}

trait ColumnarTableModule[M[+_]]
    extends TableModule[M]
    with ColumnarTableTypes
    with IdSourceScannerModule[M]
    with SliceTransforms[M]
    with YggConfigComponent {
      
  import TableModule._
  import trans._
  import trans.constants._
  
  type YggConfig <: IdSourceConfig with ColumnarTableModuleConfig

  type Table <: ColumnarTable
  type TableCompanion <: ColumnarTableCompanion
  case class TableMetrics(startCount: Int, sliceTraversedCount: Int)

  def newScratchDir(): File = Files.createTempDir()
  def jdbmCommitInterval: Long = 200000l

  implicit def liftF1(f: F1) = new F1Like {
    def compose(f1: F1) = f compose f1
    def andThen(f1: F1) = f andThen f1
  }

  implicit def liftF2(f: F2) = new F2Like {
    def applyl(cv: CValue) = new CF1(f(Column.const(cv), _))
    def applyr(cv: CValue) = new CF1(f(_, Column.const(cv)))

    def andThen(f1: F1) = new CF2((c1, c2) => f(c1, c2) flatMap f1.apply)
  }

  trait ColumnarTableCompanion extends TableCompanionLike {
    def apply(slices: StreamT[M, Slice], size: TableSize): Table
    
    def singleton(slice: Slice): Table

    implicit def groupIdShow: Show[GroupId] = Show.showFromToString[GroupId]

    def empty: Table = Table(StreamT.empty[M, Slice], ExactSize(0))
    
    def constBoolean(v: collection.Set[CBoolean]): Table = {
      val column = ArrayBoolColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(CPath.Identity, CBoolean) -> column), v.size) :: StreamT.empty[M, Slice], ExactSize(v.size))
    }

    def constLong(v: collection.Set[CLong]): Table = {
      val column = ArrayLongColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(CPath.Identity, CLong) -> column), v.size) :: StreamT.empty[M, Slice], ExactSize(v.size))
    }

    def constDouble(v: collection.Set[CDouble]): Table = {
      val column = ArrayDoubleColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(CPath.Identity, CDouble) -> column), v.size) :: StreamT.empty[M, Slice], ExactSize(v.size))
    }

    def constDecimal(v: collection.Set[CNum]): Table = {
      val column = ArrayNumColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(CPath.Identity, CNum) -> column), v.size) :: StreamT.empty[M, Slice], ExactSize(v.size))
    }

    def constString(v: collection.Set[CString]): Table = {
      val column = ArrayStrColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(CPath.Identity, CString) -> column), v.size) :: StreamT.empty[M, Slice], ExactSize(v.size))
    }

    def constDate(v: collection.Set[CDate]): Table =  {
      val column = ArrayDateColumn(v.map(_.value).toArray)
      Table(Slice(Map(ColumnRef(CPath.Identity, CDate) -> column), v.size) :: StreamT.empty[M, Slice], ExactSize(v.size))
    }

    def constArray[A: CValueType](v: collection.Set[CArray[A]]): Table = {
      val column = ArrayHomogeneousArrayColumn(v.map(_.value).toArray(CValueType[A].manifest.arrayManifest))
      Table(Slice(Map(ColumnRef(CPath.Identity, CArrayType(CValueType[A])) -> column), v.size) :: StreamT.empty[M, Slice], ExactSize(v.size))
    }

    def constNull: Table = 
      Table(Slice(Map(ColumnRef(CPath.Identity, CNull) -> new InfiniteColumn with NullColumn), 1) :: StreamT.empty[M, Slice], ExactSize(1))

    def constEmptyObject: Table = 
      Table(Slice(Map(ColumnRef(CPath.Identity, CEmptyObject) -> new InfiniteColumn with EmptyObjectColumn), 1) :: StreamT.empty[M, Slice], ExactSize(1))

    def constEmptyArray: Table = 
      Table(Slice(Map(ColumnRef(CPath.Identity, CEmptyArray) -> new InfiniteColumn with EmptyArrayColumn), 1) :: StreamT.empty[M, Slice], ExactSize(1))

    def transformStream[A](sliceTransform: SliceTransform1[A], slices: StreamT[M, Slice]): StreamT[M, Slice] = {
      def stream(state: A, slices: StreamT[M, Slice]): StreamT[M, Slice] = StreamT(
        for {
          head <- slices.uncons
        } yield {
          head map { case (s, sx) =>
            val (nextState, s0) = sliceTransform.f(state, s)
            StreamT.Yield(s0, stream(nextState, sx))
          } getOrElse {
            StreamT.Done
          }
        }
      )

      stream(sliceTransform.initial, slices)
    }

    def intersect(set: Set[NodeSubset], requiredSorts: Map[MergeNode, Set[Seq[TicVar]]]): M[NodeSubset] = {
      if (set.size == 1) {
        for {
          subset <- set.head.point[M]
          //json <- subset.table.toJson
          //_ = println("\n\nintersect of single-sorting node for groupId: " + subset.groupId + "\n" + JArray(json.toList))
        } yield subset
      } else {
        val preferredKeyOrder: Seq[TicVar] = requiredSorts(set.head.node).groupBy(a => a).mapValues(_.size).maxBy(_._2)._1

        val reindexedSubsets = set map { 
           sub => sub.copy(groupKeyTrans = sub.groupKeyTrans.alignTo(preferredKeyOrder))
        }

        val joinable = reindexedSubsets.map(_.table)
        for {
          //json <- joinable.map(_.toJson).sequence
          //_ = println("intersect-input " + reindexedSubsets.head.groupId + "\n" + json.map(_.mkString("\n")).mkString("\n===================================\n"))
          joinedTable <- intersect(reindexedSubsets.head.idTrans, joinable.toSeq: _*) 
          //jjson <- joinedTable.toJson
          //_ = println("intersect-output " + reindexedSubsets.head.groupId + "\n" + jjson.mkString("\n"))
        } yield {
          // todo: make sortedByIdentities not a boolean flag, maybe wrap groupKeyPrefix in Option
          reindexedSubsets.head.copy(table = joinedTable, groupKeyPrefix = preferredKeyOrder, sortedByIdentities = true)
        }
      }
    }

    /**
     * Intersects the given tables on identity, where identity is defined by the provided TransSpecs
     */
    def intersect(identitySpec: TransSpec1, tables: Table*): M[Table] = {
      val inputCount = tables.size
      val mergedSlices: StreamT[M, Slice] = tables.map(_.slices).reduce( _ ++ _ )
      Table(mergedSlices, UnknownSize).sort(identitySpec).map {
        sortedTable => {
          sealed trait CollapseState
          case class Boundary(prevSlice: Slice, prevStartIdx: Int) extends CollapseState
          case object InitialCollapse extends CollapseState

          def genComparator(sl1: Slice, sl2: Slice) = Slice.rowComparatorFor(sl1, sl2) { slice =>
            // only need to compare identities (field "0" of the sorted table) between projections
            // TODO: Figure out how we might do this directly with the identitySpec
            slice.columns.keys collect { case ColumnRef(path, _) if path.nodes.startsWith(CPathField("0") :: Nil) => path }
          }
          
          def boundaryCollapse(prevSlice: Slice, prevStart: Int, curSlice: Slice): (BitSet, Int) = {
            val comparator = genComparator(prevSlice, curSlice)

            var curIndex = 0

            while (curIndex < curSlice.size && comparator.compare(prevStart, curIndex) == EQ) {
              curIndex += 1
            }

            if (curIndex == 0) {
              // First element is unequal...
              // We either marked the span to retain in the previous slice, or 
              // we don't have enough here to mark the new slice to retain
              (new BitSet, curIndex)
            } else {
              val count = (prevSlice.size - prevStart) + curIndex

              if (count == inputCount) {
                val bs = new BitSet
                bs.set(curIndex - 1)
                (bs, curIndex)
              } else if (count > inputCount) {
                sys.error("Found too many EQ identities in intersect. This indicates a bug in the graph processing algorithm.")
              } else {
                (new BitSet, curIndex)
              }
            }
          } 

          // Collapse the slices, returning the BitSet for which the rows are defined as well as the start of the
          // last span 
          def selfCollapse(slice: Slice, startIndex: Int, defined: BitSet): (BitSet, Int) = {
            val comparator = genComparator(slice, slice)

            var retain = defined

            // We'll collect spans of EQ rows in chunks, retainin the start row of completed spans with the correct
            // count and then inchworming over it
            var spanStart = startIndex
            var spanEnd   = startIndex
            
            while (spanEnd < slice.size) {
              while (spanEnd < slice.size && comparator.compare(spanStart, spanEnd) == EQ) {
                spanEnd += 1
              }

              val count = spanEnd - spanStart

              if (count == inputCount) {
                retain += (spanEnd - 1)
              } else if (count > inputCount) {
                sys.error("Found too many EQ identities in intersect. This indicates a bug in the graph processing algorithm.")
              }

              if (spanEnd < slice.size) {
                spanStart = spanEnd
              }
            }

            (retain, spanStart)
          }

          val collapse = SliceTransform1[CollapseState](InitialCollapse, {
            case (InitialCollapse, slice) => {
              val (retain, spanStart) = selfCollapse(slice, 0, new BitSet)
              // Pass on the remainder, if any, of this slice to the next slice for continued comparison
              (Boundary(slice, spanStart), slice.redefineWith(retain))
            }

            case (Boundary(prevSlice, prevStart), slice) => {
              // First, do a boundary comparison on the previous slice to see if we need to retain lead elements in the new slice
              val (boundaryRetain, boundaryEnd) = boundaryCollapse(prevSlice, prevStart, slice)
              val (retain, spanStart) = selfCollapse(slice, boundaryEnd, boundaryRetain)
              (Boundary(slice, spanStart), slice.redefineWith(retain))
            }
          })

          // Break the idents out into field "0", original data in "1"
          val splitIdentsTransSpec = OuterObjectConcat(WrapObject(identitySpec, "0"), WrapObject(Leaf(Source), "1"))

          Table(transformStream(collapse, sortedTable.transform(splitIdentsTransSpec).compact(Leaf(Source)).slices), UnknownSize).transform(DerefObjectStatic(Leaf(Source), CPathField("1")))
        }
      }
    }

    ///////////////////////
    // Grouping Support //
    ///////////////////////
  
    type TicVar = CPathField

    case class MergeAlignment(left: MergeSpec, right: MergeSpec, keys: Seq[TicVar])
    
    sealed trait MergeSpec
    case class SourceMergeSpec(binding: Binding, groupKeyTransSpec: TransSpec1, order: Seq[TicVar]) extends MergeSpec
    case class LeftAlignMergeSpec(alignment: MergeAlignment) extends MergeSpec
    case class IntersectMergeSpec(mergeSpecs: Set[MergeSpec]) extends MergeSpec
    case class NodeMergeSpec(ordering: Seq[TicVar], toAlign: Set[MergeSpec]) extends MergeSpec
    case class CrossMergeSpec(left: MergeSpec, right: MergeSpec) extends MergeSpec
    
    // The GroupKeySpec for a binding is comprised only of conjunctions that refer only
    // to members of the source table. The targetTrans defines a transformation of the
    // table to be used as the value output after keys have been derived. 
    // while Binding as the same general structure as GroupingSource, we keep it as a seperate type because
    // of the constraint that the groupKeySpec must be a conjunction, or just a single source clause. Reusing
    // the same type would be confusing
    case class Binding(source: Table, idTrans: TransSpec1, targetTrans: Option[TransSpec1], groupId: GroupId, groupKeySpec: GroupKeySpec) 

    // MergeTrees describe intersections as edges in a graph, where the nodes correspond
    // to sets of bindings
    case class MergeNode(keys: Set[TicVar], binding: Binding) {
      def ticVars = keys
      def groupId = binding.groupId
      def describe = binding.groupId + ": " + keys
    }
    object MergeNode {
      def apply(binding: Binding): MergeNode = MergeNode(Universe.sources(binding.groupKeySpec).map(_.key).toSet, binding)
    }

    /**
     * Represents an adjaceny based on a common subset of TicVars
     */
    class MergeEdge private[MergeEdge](val a: MergeNode, val b: MergeNode) {
      /** The common subset of ticvars shared by both nodes */
      val sharedKeys = a.keys & b.keys

      /** The set of nodes joined by this edge */
      val nodes = Set(a, b)
      def touches(node: MergeNode) = nodes.contains(node)

      /** The total set of keys joined by this edge (for alignment) */
      val keys: Set[TicVar] = a.keys ++ b.keys

      def joins(x: MergeNode, y: MergeNode) = (x == a && y == b) || (x == b && y == a)

      // Overrrides for set equality
      override def equals(other: Any) = other match {
        case e: MergeEdge => e.nodes == this.nodes
        case _ => false
      }
      override def hashCode() = nodes.hashCode()
      override def toString() = "MergeEdge(%s, %s)".format(a, b)
    }

    object MergeEdge {
      def apply(a: MergeNode, b: MergeNode) = new MergeEdge(a, b)
      def unapply(n: MergeEdge): Option[(MergeNode, MergeNode)] = Some((n.a, n.b))
    }

    // A maximal spanning tree for a merge graph, where the edge weights correspond
    // to the size of the shared keyset for that edge. We use hte maximal weights
    // since the larger the set of shared keys, the fewer constraints are imposed
    // making it more likely that a sorting for those shared keys can be reused.
    case class MergeGraph(nodes: Set[MergeNode], edges: Set[MergeEdge] = Set()) {
      def join(other: MergeGraph, edge: MergeEdge) = MergeGraph(nodes ++ other.nodes, edges ++ other.edges + edge)

      val edgesFor: Map[MergeNode, Set[MergeEdge]] = edges.foldLeft(nodes.map((_, Set.empty[MergeEdge])).toMap) {
        case (acc, edge @ MergeEdge(a, b)) => 
          val aInbound = acc(a) + edge
          val bInbound = acc(b) + edge
          acc + (a -> aInbound) + (b -> bInbound)
      }

      def adjacent(a: MergeNode, b: MergeNode) = {
        edges.find { e => (e.a == a && e.b == a) || (e.a == b && e.b == a) }.isDefined
      }

      val rootNode = (edgesFor.toList maxBy { case (_, edges) => edges.size })._1
    }

    case class Universe(bindings: List[Binding]) {
      import Universe._

      def spanningGraphs: Set[MergeGraph] = {
        val clusters: Map[MergeNode, List[Binding]] = bindings groupBy { 
          case binding @ Binding(_, _, _, _, groupKeySpec) => MergeNode(sources(groupKeySpec).map(_.key).toSet, binding) 
        }

        findSpanningGraphs(edgeMap(clusters.keySet))
      }
    }

    object Universe {
      def allEdges(nodes: collection.Set[MergeNode]): collection.Set[MergeEdge] = {
        for {
          l <- nodes
          r <- nodes
          if l != r
          sharedKey = l.keys intersect r.keys
          if sharedKey.nonEmpty
        } yield {
          MergeEdge(l, r)
        }
      }

      def edgeMap(nodes: collection.Set[MergeNode]): Map[MergeNode, Set[MergeEdge]] = {
        allEdges(nodes).foldLeft(nodes.map(n => n -> Set.empty[MergeEdge]).toMap) { 
          case (acc, edge @ MergeEdge(a, b)) => acc + (a -> (acc.getOrElse(a, Set()) + edge)) + (b -> (acc.getOrElse(b, Set()) + edge))
        } 
      }

      // a universe is a conjunction of binding clauses, which must contain no disjunctions
      def sources(spec: GroupKeySpec): Seq[GroupKeySpecSource] = (spec: @unchecked) match {
        case GroupKeySpecAnd(left, right) => sources(left) ++ sources(right)
        case src: GroupKeySpecSource => Vector(src)
      }

      // An implementation of our algorithm for finding a minimally connected set of graphs
      def findSpanningGraphs(outbound: Map[MergeNode, Set[MergeEdge]]): Set[MergeGraph] = {
        def isConnected(from: MergeNode, to: MergeNode, outbound: Map[MergeNode, Set[MergeEdge]], constraintSet: Set[TicVar]): Boolean = {
          outbound.getOrElse(from, Set()).exists {
            case edge @ MergeEdge(a, b) => 
              a == to || b == to ||
              {
                val other = if (a == from) b else a
                // the other node's keys must be a superset of the constraint set we're concerned with in order to traverse it.
                ((other.keys & constraintSet) == constraintSet) && {
                  val pruned = outbound mapValues { _ - edge } map identity
                  isConnected(other,to, pruned, constraintSet)
                }
              }
          }
        }

        def find0(outbound: Map[MergeNode, Set[MergeEdge]], edges: Set[MergeEdge]): Map[MergeNode, Set[MergeEdge]] = {
          if (edges.isEmpty) {
            outbound
          } else {
            val edge = edges.head

            // node we're searching from
            val fromNode = edge.a
            val toNode = edge.b

            val pruned = outbound mapValues { _ - edge } map identity

            find0(if (isConnected(fromNode, toNode, pruned, edge.keys)) pruned else outbound, edges.tail)
          }
        }

        def partition(in: Map[MergeNode, Set[MergeEdge]]): Set[MergeGraph] = {
          in.values.flatten.foldLeft(in.keySet map { k => MergeGraph(Set(k)) }) {
            case (acc, edge @ MergeEdge(a, b)) => 
              val g1 = acc.find(_.nodes.contains(a)).get
              val g2 = acc.find(_.nodes.contains(b)).get

              val resultGraph = g1.join(g2, edge)
              acc - g1 - g2 + resultGraph
          }
        }

        partition(find0(outbound, outbound.values.flatten.toSet))
      }
    }


    // BorgResult tables must have the following structure with respect to the root:
    // {
    //   "groupKeys": { "000000": ..., "000001": ... },
    //   "identities": { "<string value of groupId1>": <identities for groupId1>, "<string value of groupId2>": ... },
    //   "values": { "<string value of groupId1>": <values for groupId1>, "<string value of groupId2>": ... },
    // }
    case class BorgResult(table: Table, groupKeys: Seq[TicVar], groups: Set[GroupId], size: TableSize = UnknownSize, sorted: Boolean = false)

    object BorgResult {
      val allFields = Set(CPathField("groupKeys"), CPathField("identities"), CPathField("values"))

      def apply(nodeSubset: NodeSubset): BorgResult = {
        assert(!nodeSubset.sortedByIdentities)
        val groupId = nodeSubset.node.binding.groupId

        val trans = OuterObjectConcat(
          wrapGroupKeySpec(nodeSubset.groupKeyTrans.spec) ::
          wrapIdentSpec(nestInGroupId(nodeSubset.idTrans, groupId)) ::
          nodeSubset.targetTrans.map(t => wrapValueSpec(nestInGroupId(t, groupId))).toList : _*
        )

        BorgResult(nodeSubset.table.transform(trans), 
                   nodeSubset.groupKeyTrans.keyOrder, 
                   Set(groupId),
                   nodeSubset.size,
                   sorted = true)
      }

      def groupKeySpec[A <: SourceType](source: A) = DerefObjectStatic(Leaf(source), CPathField("groupKeys"))
      def identSpec[A <: SourceType](source: A) = DerefObjectStatic(Leaf(source), CPathField("identities"))
      def valueSpec[A <: SourceType](source: A) = DerefObjectStatic(Leaf(source), CPathField("values"))

      def wrapGroupKeySpec[A <: SourceType](source: TransSpec[A]) = WrapObject(source, "groupKeys")
      def wrapIdentSpec[A <: SourceType](source: TransSpec[A]) = WrapObject(source, "identities")
      def wrapValueSpec[A <: SourceType](source: TransSpec[A]) = WrapObject(source, "values")

      def nestInGroupId[A <: SourceType](source: TransSpec[A], groupId: GroupId) = WrapObject(source, groupId.shows)
    }

    case class OrderingConstraint(ordering: Seq[Set[TicVar]]) { self =>
      // Fix this binding constraint into a sort order. Any non-singleton TicVar sets will simply
      // be converted into an arbitrary sequence
      lazy val fixed = ordering.flatten

      def & (that: OrderingConstraint): Option[OrderingConstraint] = OrderingConstraints.replacementFor(self, that)

      def - (ticVars: Set[TicVar]): OrderingConstraint = OrderingConstraint(ordering.map(_.filterNot(ticVars.contains)).filterNot(_.isEmpty))

      override def toString = ordering.map(_.map(_.toString.substring(1)).mkString("{", ", ", "}")).mkString("OrderingConstraint(", ",", ")")
    }

    object OrderingConstraint {
      val Zero = OrderingConstraint(Vector.empty)

      def fromFixed(order: Seq[TicVar]): OrderingConstraint = OrderingConstraint(order.map(v => Set(v)))
    }

    /*
    sealed trait OrderingConstraint2 { self =>
      import OrderingConstraint2._

      def fixed: Seq[TicVar]

      // Fixes this one to the specified ordering:
      def fixedFrom(fixed: Seq[TicVar]): Option[Seq[TicVar]] = {
        val commonVariables = fixed.toSet intersect self.variables

        val joined = Ordered.fromVars(fixed) join self
        
        if (joined.success(commonVariables)) {
          Some(ordered(joined.join, joined.rightRem).fixed)
        } else None
      }

      def normalize: OrderingConstraint2

      def flatten: OrderingConstraint2

      def render: String

      def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2

      def - (thatVars: Set[TicVar]): OrderingConstraint2 = {
        (filter {
          case OrderingConstraint2.Variable(x) => !thatVars.contains(x)

          case x => true
        }).normalize
      }

      lazy val fixedConstraint = OrderingConstraint2.orderedVars(fixed: _*)

      lazy val variables: Set[TicVar] = fixed.toSet

      lazy val size = fixed.size      

      def & (that: OrderingConstraint2): Option[OrderingConstraint2] = {
        val joined = self.join(that)

        if (joined.success && joined.leftRem == Zero && joined.rightRem == Zero) Some(joined.join)
        else None
      }

      def join(that: OrderingConstraint2): Join = {
        def joinSet(constructJoin: (OrderingConstraint2, OrderingConstraint2) => OrderingConstraint2)(lastJoin: Join, choices: Set[OrderingConstraint2]): Join = {

          // Tries to join the maximal number of elements from "remaining" into lastJoin:
          def joinSet0(lastJoin: Join, choices: Set[OrderingConstraint2]): Set[(Join, Set[OrderingConstraint2])] = {
            val default = Set((lastJoin, choices))

            if (lastJoin.leftRem == Zero) {
              default
            } else {
              choices.foldLeft(default) { 
                case (solutions, choice) =>
                  val nextChoices = choices - choice

                  val newJoin = lastJoin.leftRem.join(choice)

                  solutions ++ (if (newJoin.success) {
                    joinSet0(
                      Join(
                        join     = constructJoin(lastJoin.join, newJoin.join), 
                        leftRem  = newJoin.leftRem, 
                        rightRem = unordered(lastJoin.rightRem, newJoin.rightRem)
                      ),
                      nextChoices
                    )
                  } else {
                    Set.empty
                  })
              }
            }
          }

          val (join, rightRem) = joinSet0(lastJoin, choices).toSeq.maxBy(_._1.size)

          join.copy(rightRem = unordered(join.rightRem, Unordered(rightRem)))
        }

        def join2(left: OrderingConstraint2, right: OrderingConstraint2): Join = {
          (left, right) match {
            case (left, Zero) => Join(left)

            case (Zero, right) => Join(right)

            case (l @ Ordered(left), r @ Ordered(right)) => 
              val joinedHeads = left.head.join(right.head)

              if (joinedHeads.failure) Join.unjoined(l, r)
              else {
                val leftTail = ordered(joinedHeads.leftRem, Ordered(left.tail))
                val rightTail = ordered(joinedHeads.rightRem, Ordered(right.tail))

                // In some cases, the tails may not join, that's OK though because we've joined the heads already.
                val joinedTails = leftTail.join(rightTail)

                Join(ordered(joinedHeads.join, joinedTails.join), joinedTails.leftRem, joinedTails.rightRem)
              }

            case (l @ Ordered(left), r @ Variable(right)) => 
              if (left.head == r) Join(r, leftRem = Ordered(left.tail), rightRem = Zero) else Join.unjoined(l, r)

            case (l @ Variable(left), r @ Unordered(right)) => 
              joinSet((a, b) => ordered(a, b))(Join(Zero, l, Zero), right)
              //if (right.contains(l)) Join(l, leftRem = Zero, rightRem = Unordered(right - l)) else Join.unjoined(l, r)

            case (l @ Ordered(left), r @ Unordered(right)) => 
              joinSet((a, b) => ordered(a, b))(Join(Zero, l, Zero), right)

            case (l @ Unordered(left), r @ Unordered(right)) => 
              joinSet((a, b) => unordered(a, b))(Join(Zero, l, Zero), right)

            case (l @ Variable(left), r @ Variable(right)) => 
              if (left == right) Join(l) else Join.unjoined(l, r)

            case (l @ Variable(left), r @ Ordered(right)) => 
              join2(r, l).flip

            case (l @ Unordered(left), r @ Ordered(right)) => 
              join2(r, l).flip

            case (l @ Unordered(left), r @ Variable(right)) => 
              join2(r, l).flip
          }
        }

        join2(self.normalize, that.normalize).normalize
      }
    }

    object OrderingConstraint2 {
      case class Join(join: OrderingConstraint2, leftRem: OrderingConstraint2 = Zero, rightRem: OrderingConstraint2 = Zero) {
        def normalize = copy(join = join.normalize, leftRem = leftRem.normalize, rightRem = rightRem.normalize)

        def flip = copy(leftRem = rightRem, rightRem = leftRem)

        def size = join.size

        def success = join != Zero

        def success(variables: Set[TicVar]): Boolean = {
          success && {
            (join.variables intersect variables).size == variables.size
          }
        }

        def failure = !success

        def failure(variables: Set[TicVar]) = !success(variables)

        def collapse: OrderingConstraint2 = ordered(join, unordered(leftRem, rightRem))
      }

      object Join {
        def unjoined(left: OrderingConstraint2, right: OrderingConstraint2): Join = Join(Zero, left, right)
      }

      def ordered(values: OrderingConstraint2*) = Ordered(Vector(values: _*))

      def orderedVars(values: TicVar*) = Ordered(Vector(values: _*).map(Variable.apply))

      def unordered(values: OrderingConstraint2*) = Unordered(values.toSet)

      def unorderedVars(values: TicVar*) = Unordered(values.toSet.map(Variable.apply))

      case object Zero extends OrderingConstraint2 { self =>
        def fixed = Vector.empty

        def flatten = self

        def normalize = self

        def render = "*"

        def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2 = Zero
      }

      case class Variable(value: TicVar) extends OrderingConstraint2 { self =>
        def fixed = Vector(value)

        def flatten: Variable = self

        def normalize = self

        def render = "'" + value.toString.substring(1)

        def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2 = pf.lift(self).filter(_ == true).map(Function.const(self)).getOrElse(Zero)
      }
      case class Ordered(value: Seq[OrderingConstraint2]) extends OrderingConstraint2 { self =>
        def fixed = value.map(_.fixed).flatten

        def normalize = {
          val f = Ordered(value.map(_.normalize)).flatten
          val fv = f.value

          if (fv.length == 0) Zero
          else if (fv.length == 1) fv.head 
          else f
        }

        def flatten: Ordered = Ordered(value.map(_.flatten).flatMap {
          case x: Ordered => x.value
          case Zero => Vector.empty
          case x => Vector(x)
        })

        def render = value.map(_.render).mkString("[", ", ", "]")

        def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2 = {
          val self2 = Ordered(value.map(_.filter(pf)))

          pf.lift(self2).filter(_ == true).map(Function.const(self2)).getOrElse(Zero)
        }
      }
      object Ordered {
        def fromVars(seq: Seq[TicVar]) = Ordered(seq.map(Variable(_)))
      }
      case class Unordered(value: Set[OrderingConstraint2]) extends OrderingConstraint2 { self =>
        def fixed = value.toSeq.map(_.fixed).flatten

        def flatten: Unordered = Unordered(value.map(_.flatten).flatMap {
          case x: Unordered => x.value
          case Zero => Set.empty[OrderingConstraint2]
          case x => Set(x)
        })

        def normalize = {
          val f = Unordered(value.map(_.normalize)).flatten
          val fv = f.value

          if (fv.size == 0) Zero
          else if (fv.size == 1) fv.head 
          else f
        }

        def render = value.toSeq.sortBy(_.render).map(_.render).mkString("{", ", ", "}")

        def filter(pf: PartialFunction[OrderingConstraint2, Boolean]): OrderingConstraint2 = {
          val self2 = Unordered(value.map(_.filter(pf)))

          pf.lift(self2).filter(_ == true).map(Function.const(self2)).getOrElse(Zero)
        }
      }
      object Unordered {
        def fromVars(set: Set[TicVar]) = Unordered(set.map(Variable(_)))
      }
    }
    */

    object OrderingConstraints {
      def findCompatiblePrefix(a: Set[Seq[TicVar]], b: Set[Seq[TicVar]]): Option[Seq[TicVar]] = {
        @tailrec def compatiblePrefix(sa: Seq[TicVar], sb: Seq[TicVar], acc: Seq[TicVar]): Option[Seq[TicVar]] = {
          if (sa.isEmpty || sb.isEmpty) {
            Some(acc)
          } else if (sa.head == sb.head) {
            compatiblePrefix(sa.tail, sb.tail, acc :+ sa.head)
          } else {
            (sa.toSet intersect sb.toSet).isEmpty.option(acc)
          }
        }

        val found = for (sa <- a; sb <- b; result <- compatiblePrefix(sa, sb, Vector())) yield result
        if (found.isEmpty) None else Some(found.maxBy(_.size))
      }

      def findLongestPrefix(unconstrained: Set[TicVar], from: Set[Seq[TicVar]]): Option[Seq[TicVar]] = {
        @tailrec def compatiblePrefix(sa: Set[TicVar], suf: Seq[TicVar], acc: Seq[TicVar]): Option[Seq[TicVar]] = {
          if (suf.isEmpty) Some(acc)
          else if (sa.contains(suf.head)) compatiblePrefix(sa - suf.head, suf.tail, acc :+ suf.head)
          else if ((sa intersect suf.toSet).isEmpty) Some(acc)
          else None
        }

        val found = from.flatMap(compatiblePrefix(unconstrained, _, Vector()))
        if (found.isEmpty) None else Some(found.maxBy(prefix => (prefix diff unconstrained.toSeq).size))
      }

      /**
       * Compute a new constraint that can replace both input constraints
       */
      def replacementFor(a: OrderingConstraint, b: OrderingConstraint): Option[OrderingConstraint] = {
        @tailrec
        def alignConstraints(left: Seq[Set[TicVar]], right: Seq[Set[TicVar]], computed: Seq[Set[TicVar]] = Seq()): Option[OrderingConstraint] = {
          if (left.isEmpty) {
            // left is a prefix or equal to the shifted right, so we can use computed :: right as our common constraint
            Some(OrderingConstraint(computed ++ right))
          } else if (right.isEmpty) {
            Some(OrderingConstraint(computed ++ left))
          } else {
            val intersection = left.head & right.head
            val diff = right.head diff left.head
            if (intersection == left.head) {
              // If left's head is a subset of right's, we can split right's head, use the subset as the next part
              // of our computed sequence, then push the unused portion back onto right for another round of alignment
              val newRight = if (diff.nonEmpty) diff +: right.tail else right.tail
              alignConstraints(left.tail, newRight, computed :+ intersection)
            } else {
              // left is not a subset, so these constraints can't be aligned
              None
            }
          }
        }

        alignConstraints(a.ordering, b.ordering) orElse alignConstraints(b.ordering, a.ordering)
      }

      /**
       * Given the set of input constraints, find a _minimal_ set of compatible OrderingConstraints that
       * covers that set.
       */
      def minimize(constraints: Set[OrderingConstraint]): Set[OrderingConstraint] = {
        @tailrec
        def reduce(unreduced: Set[OrderingConstraint], minimized: Set[OrderingConstraint]): Set[OrderingConstraint] = {
          if (unreduced.isEmpty) {
            minimized
          } else {
            // Find the first constraint in the tail that can be reduced with the head
            unreduced.tail.iterator.map { c => (c, replacementFor(c, unreduced.head)) } find { _._2.isDefined } match {
              // We have a reduction, so re-run, replacing the two reduced constraints with the newly compute one
              case Some((other, Some(reduced))) => reduce(unreduced -- Set(other, unreduced.head) + reduced, minimized)
              // No reduction possible, so head is part of the minimized set
              case _ => reduce(unreduced.tail, minimized + unreduced.head)
            }
          }
        }

        reduce(constraints, Set())
      }
    }

    // todo: Maybe make spec an ArrayConcat?
    case class GroupKeyTrans(spec: TransSpec1, keyOrder: Seq[TicVar]) {
      import GroupKeyTrans._

      def alignTo(targetOrder: Seq[TicVar]): GroupKeyTrans = {
        if (keyOrder == targetOrder) this else {
          val keyMap = targetOrder.zipWithIndex.toMap
          val newOrder = keyOrder.sortBy(key => keyMap.getOrElse(key, Int.MaxValue))

          val keyComponents = newOrder.zipWithIndex map { 
            case (ticvar, i) => reindex(spec, keyOrder.indexOf(ticvar), i)
          }

          GroupKeyTrans(
            OuterObjectConcat(keyComponents: _*),
            newOrder
          )
        }
      }

      def prefixTrans(length: Int): TransSpec1 = {
        if (keyOrder.size == length) spec else {
          OuterObjectConcat((0 until length) map { i => reindex(spec, i, i) }: _*)
        }
      }
    }

    object GroupKeyTrans {
      // 999999 ticvars should be enough for anybody.
      def keyName(i: Int) = "%06d".format(i)
      def keyVar(i: Int): TicVar = CPathField(keyName(i))

      def reindex[A <: SourceType](spec: TransSpec[A], from: Int, to: Int) = WrapObject(DerefObjectStatic(spec, keyVar(from)), keyName(to))

      // the GroupKeySpec passed to deriveTransSpecs must be either a source or a conjunction; all disjunctions
      // have been factored out by this point
      def apply(conjunction: Seq[GroupKeySpecSource]): GroupKeyTrans = {
        // [avalue, bvalue]
        val (keySpecs, fullKeyOrder) = conjunction.zipWithIndex.map({ case (src, i) => WrapObject(src.spec, keyName(i)) -> src.key }).unzip
        val groupKeys = OuterObjectConcat(keySpecs: _*)

        GroupKeyTrans(groupKeys, fullKeyOrder)
      }
    }


    //sealed trait NodeMetadata {
    //  def size: Long

    //  def nodeOrderOpt: Option[Seq[TicVar]]
    //}

    //object NodeMetadata {
    //  def apply(size0: Long, nodeOrderOpt0: Option[Seq[TicVar]]) = new NodeMetadata {
    //    def size = size0

    //    def nodeOrderOpt = nodeOrderOpt0
    //  }
    //}

    case class NodeSubset(node: MergeNode, table: Table, idTrans: TransSpec1, targetTrans: Option[TransSpec1], groupKeyTrans: GroupKeyTrans, groupKeyPrefix: Seq[TicVar], sortedByIdentities: Boolean = false, size: TableSize = UnknownSize) {
      def sortedOn = groupKeyTrans.alignTo(groupKeyPrefix).prefixTrans(groupKeyPrefix.size)

      def groupId = node.binding.groupId
    }

    /////////////////
    /// functions ///
    /////////////////

    def findBindingUniverses(grouping: GroupingSpec): Seq[Universe] = {
      @inline def find0(v: Vector[(GroupingSource, Vector[GroupKeySpec])]): Stream[Universe] = {
        val protoUniverses = (v map { case (src, specs) => specs map { (src, _) } toStream } toList).sequence 
        
        protoUniverses map { proto =>
          Universe(proto map { case (src, spec) => Binding(src.table, src.idTrans, src.targetTrans, src.groupId, spec) })
        }
      }

      import GroupKeySpec.{dnf, toVector}
      find0(grouping.sources map { source => (source, ((dnf _) andThen (toVector _)) apply source.groupKeySpec) })
    }

    def findRequiredSorts(spanningGraph: MergeGraph): Map[MergeNode, Set[Seq[TicVar]]] = {
      findRequiredSorts(spanningGraph, spanningGraph.nodes.toList)
    }

    private[table] def findRequiredSorts(spanningGraph: MergeGraph, nodeList: List[MergeNode]): Map[MergeNode, Set[Seq[TicVar]]] = {
      import OrderingConstraints.minimize
      def inPrefix(seq: Seq[TicVar], keys: Set[TicVar], acc: Seq[TicVar] = Vector()): Option[Seq[TicVar]] = {
        if (keys.isEmpty) Some(acc) else {
          seq.headOption.flatMap { a => 
            if (keys.contains(a)) inPrefix(seq.tail, keys - a, acc :+ a) else None
          }
        }
      }

      def fix(nodes: List[MergeNode], underconstrained: Map[MergeNode, Set[OrderingConstraint]]): Map[MergeNode, Set[OrderingConstraint]] = {
        if (nodes.isEmpty) underconstrained else {
          val node = nodes.head
          val fixed = minimize(underconstrained(node)).map(_.ordering.flatten)
          val newUnderconstrained = spanningGraph.edgesFor(node).foldLeft(underconstrained) {
            case (acc, edge @ MergeEdge(a, b)) => 
              val other = if (a == node) b else a
              val edgeConstraint: Seq[TicVar] = 
                fixed.view.map(inPrefix(_, edge.sharedKeys)).collect({ case Some(seq) => seq }).head

              acc + (other -> (acc.getOrElse(other, Set()) + OrderingConstraint(edgeConstraint.map(Set(_)))))
          }

          fix(nodes.tail, newUnderconstrained)
        }
      }

      if (spanningGraph.edges.isEmpty) {
        spanningGraph.nodes.map(_ -> Set.empty[Seq[TicVar]]).toMap
      } else {
        val unconstrained = spanningGraph.edges.foldLeft(Map.empty[MergeNode, Set[OrderingConstraint]]) {
          case (acc, edge @ MergeEdge(a, b)) =>
            val edgeConstraint = OrderingConstraint(Seq(edge.sharedKeys))
            val aConstraints = acc.getOrElse(a, Set()) + edgeConstraint
            val bConstraints = acc.getOrElse(b, Set()) + edgeConstraint

            acc + (a -> aConstraints) + (b -> bConstraints)
        }
        
        fix(nodeList, unconstrained).mapValues(s => minimize(s).map(_.fixed)).map(identity)
      }
    }

    def filteredNodeSubset(node: MergeNode): NodeSubset = {
      val protoGroupKeyTrans = GroupKeyTrans(Universe.sources(node.binding.groupKeySpec))

      // Since a transspec for a group key may perform a bunch of work (computing values, etc)
      // it seems like we really want to do that work only once; prior to the initial sort. 
      // This means carrying around the *computed* group key everywhere
      // post the initial sort separate from the values that it was derived from. 
      val (payloadTrans, idTrans, targetTrans, groupKeyTrans) = node.binding.targetTrans match {
        case Some(targetSetTrans) => 
          val payloadTrans = ArrayConcat(WrapArray(node.binding.idTrans), 
                                         WrapArray(protoGroupKeyTrans.spec), 
                                         WrapArray(targetSetTrans))

          (payloadTrans,
           TransSpec1.DerefArray0, 
           Some(TransSpec1.DerefArray2), 
           GroupKeyTrans(TransSpec1.DerefArray1, protoGroupKeyTrans.keyOrder))

        case None =>
          val payloadTrans = ArrayConcat(WrapArray(node.binding.idTrans), WrapArray(protoGroupKeyTrans.spec))
          (payloadTrans, 
           TransSpec1.DerefArray0, 
           None, 
           GroupKeyTrans(TransSpec1.DerefArray1, protoGroupKeyTrans.keyOrder))
      }

      val requireFullGroupKeyTrans = Scan(
        payloadTrans,
        new CScanner {
          type A = Unit

          val init = ()
          private val keyIndices = (0 until groupKeyTrans.keyOrder.size).toSet

          def scan(a: Unit, cols: Map[ColumnRef, Column], range: Range) = {
            // Get mappings from each ticvar to columns that define it.
            val ticVarColumns =
              cols.toList.collect { 
                case (ref @ ColumnRef(CPath(CPathIndex(1), CPathField(ticVarIndex), _ @ _*), _), col) => (ticVarIndex, col)
              }.groupBy(_._1).mapValues(_.unzip._2).map(identity)

            if (ticVarColumns.keySet.map(_.toInt) == keyIndices) {
              //println("All group key columns present:\n  " + (new Slice { val size = range.end; val columns = cols }))
              // all group key columns are present, so we can use filterDefined
              val defined = BitSetUtil.filteredRange(range.start, range.end) {
                i => ticVarColumns.forall { 
                  case (ticvar, columns) => 
                    val ret = columns.map(_.isDefinedAt(i))
                    ret.exists(_ == true)
                }
              }

              //println("Defined for " + defined)

              ((), cols.lazyMapValues { col => cf.util.filter(range.start, range.end, defined)(col).get })
            } else {
              ((), Map.empty[ColumnRef, Column])
            }
          }
        }
      )

      NodeSubset(node,
                 node.binding.source.transform(requireFullGroupKeyTrans),
                 idTrans,
                 targetTrans,
                 groupKeyTrans,
                 groupKeyTrans.keyOrder,
                 size = node.binding.source.size)
    }


    /**
     * Perform the sorts required for the specified node (needed to align this node with each
     * node to which it is connected) and return as a map from the sort ordering for the connecting
     * edge to the sorted table with the appropriate dereference transspecs.
     */
    def materializeSortOrders(node: MergeNode, requiredSorts: Set[Seq[TicVar]]): M[Map[Seq[TicVar], NodeSubset]] = {
      val ns = filteredNodeSubset(node)
      val NodeSubset(node0, filteredSource, idTrans, targetTrans, groupKeyTrans, _, _, _) = ns

      val orderedTicVars = requiredSorts.toList
      val sortTransSpecs = orderedTicVars map { ticvars => groupKeyTrans.alignTo(ticvars).prefixTrans(ticvars.length) }

      filteredSource.groupByN(sortTransSpecs, Leaf(Source)) map { tables =>
        (orderedTicVars zip tables).map({ case (ticvars, sortedTable) =>
          ticvars ->
          NodeSubset(node0,
                     sortedTable,
                     idTrans,
                     targetTrans,
                     groupKeyTrans,
                     ticvars,
                     size = sortedTable.size)
        }).toMap
      }
    }

    def alignOnEdges(spanningGraph: MergeGraph, requiredSorts: Map[MergeNode, Set[Seq[TicVar]]]): M[Map[GroupId, Set[NodeSubset]]] = {
      import OrderingConstraints._

      if (spanningGraph.edges.isEmpty) {
        assert(spanningGraph.nodes.size == 1)
        val node = spanningGraph.nodes.head
        Map(node.binding.groupId -> Set(filteredNodeSubset(node))).point[M]
      } else {
        val materialized = requiredSorts.map({ case (node, orders) => materializeSortOrders(node, orders) map { node -> _ }})
        val sortPairs: M[Map[MergeNode, Map[Seq[TicVar], NodeSubset]]] = materialized.toStream.sequence.map(_.toMap)
        
        for {
          sorts <- sortPairs
          // _ = println("sorts: " + System.currentTimeMillis) 
          groupedSubsets <- {
            val edgeAlignments = spanningGraph.edges flatMap {
              case MergeEdge(a, b) =>
                // Find the compatible sortings for this edge's endpoints
                val common: Set[(NodeSubset, NodeSubset)] = for {
                  aVars <- sorts(a).keySet
                  bVars <- sorts(b).keySet
                  if aVars.startsWith(bVars) || bVars.startsWith(aVars)
                } yield {
                  (sorts(a)(aVars), sorts(b)(bVars))
                }

                common map {
                  case (aSorted, bSorted) => 
                    for {
                      //ljson <- aSorted.table.slices.toStream
                      //_ = println("=============================================================")
                      //_ = println("using merge edge (" + a.describe + " with " + aSorted.groupKeyPrefix + ")-(" + b.describe + " with " + bSorted.groupKeyPrefix + ")")
                      //_ = println(aSorted.sortedOn)
                      //_ = println("lsorted\n" + ljson.map(_.toJsonString()).mkString("\n---\n"))
                      //rjson <- bSorted.table.slices.toStream
                      //_ = println(bSorted.sortedOn)
                      //_ = println("rsorted\n" + rjson.map(_.toJsonString()).mkString("\n---\n"))
                      aligned <- Table.align(aSorted.table, aSorted.sortedOn, bSorted.table, bSorted.sortedOn)
                      //aljson <- aligned._1.slices.toStream
                      //_ = println("laligned\n" + aljson.map(_.toJsonString()).mkString("\n---\n"))
                      //arjson <- aligned._2.slices.toStream
                      //_ = println("raligned\n" + arjson.map(_.toJsonString()).mkString("\n---\n"))
                    } yield {
                      List(
                        aSorted.copy(table = aligned._1),
                        bSorted.copy(table = aligned._2)
                      )
                    }
                }
            }

            edgeAlignments.sequence
          }
        } yield {
          val flattened = groupedSubsets.flatten
          //println("grouped subsets: " + flattened.map(_.groupId))
          flattened.groupBy(_.groupId)
        }
      }
    }

/*
    // 
    // Represents the cost of a particular borg traversal plan, measured in terms of IO.
    // Computational complexity of algorithms occurring in memory is neglected. 
    // This should be thought of as a rough approximation that eliminates stupid choices.
    // 
    final case class BorgTraversalCostModel private (ioCost: Long, size: Long, ticVars: Set[TicVar]) { self =>
      // Computes a new model derived from this one by cogroup with the specified set.
      def consume(rightSize: Long, rightTicVars: Set[TicVar], accResort: Boolean, nodeResort: Boolean): BorgTraversalCostModel = {
        val commonTicVars = self.ticVars intersect rightTicVars

        val unionTicVars = self.ticVars ++ rightTicVars

        val uniqueTicVars = unionTicVars -- commonTicVars

        // TODO: Develop a better model!
        val newSize = self.size.max(rightSize) * (uniqueTicVars.size + 1)

        val newIoCost = if (!accResort) {
          3 * rightSize
        } else {
          val inputCost = self.size + rightSize
          val resortCost = self.size * 2
          val outputCost = newSize

          inputCost + resortCost + outputCost
        }

        BorgTraversalCostModel(self.ioCost + newIoCost, newSize, self.ticVars ++ rightTicVars)
      }
    }

    object BorgTraversalCostModel {
      val Zero = new BorgTraversalCostModel(0, 0, Set.empty)
    }

    // 
    // Represents a step in a borg traversal plan. The step is defined by the following elements:
    // 
    //  1. The order of the accumulator prior to executing the step.
    //  2. The order of the accumulator after executing the step.
    //  3. The node being incorporated into the accumulator during this step.
    //  4. The tic variables of the node being incorporated into this step.
    //  5. The ordering of the node required for cogroup.
    // 
    case class BorgTraversalPlanUnfixed(priorPlan: Option[BorgTraversalPlanUnfixed], node: MergeNode, nodeOrder: OrderingConstraint2, accOrderPost: OrderingConstraint2, costModel: BorgTraversalCostModel, accResort: Boolean, nodeResort: Boolean) { self =>
      import OrderingConstraint2._

      def accOrderPre: OrderingConstraint2 = priorPlan.map(_.accOrderPost).getOrElse(OrderingConstraint2.Zero)

      def accSortOrder = accOrderPost - newTicVars

      def consume(node: MergeNode, nodeSize: Long, nodeOrderOpt: Option[Seq[TicVar]]): BorgTraversalPlanUnfixed = {

        val (accOrderPost, nodeOrder, accResort, nodeResort) = {

          val commonVariables = postTicVars intersect node.ticVars


          val otherVariables = (postTicVars union node.ticVars) diff commonVariables


          val nodeUniqueVariables = node.ticVars diff commonVariables


          val initialNodeOrder = nodeOrderOpt.map(nodeOrder => Ordered.fromVars(nodeOrder))

          initialNodeOrder.map(o => (o, self.accOrderPost join o)).filter(_._2.success(commonVariables)).map {
            case (nodeOrder, join) =>
              (join.collapse, nodeOrder, false, false)
          }.getOrElse {
            val minimalJoinConstraint = ordered(Unordered.fromVars(commonVariables), Unordered.fromVars(otherVariables))


            // Can, in theory, we reuse the node ordering?
            initialNodeOrder.map { o => (o, (o join minimalJoinConstraint)) }.filter(_._2.success(commonVariables)).map {
              case (nodeOrder, nodeJoin) =>
                // Have to resort the acc, because the node order is compatible with the minimal join constraint:
                (minimalJoinConstraint, nodeOrder, true, false)
            }.getOrElse {
              // Can, in theory, we reuse the accumulator ordering?
              val accJoin = (self.accOrderPost join minimalJoinConstraint)



              // MUST sort node, it's new order is minimally constrained:
              val nodeOrder = ordered(Unordered.fromVars(commonVariables), Unordered.fromVars(nodeUniqueVariables))


              if (accJoin.success(commonVariables)) {  
                ((self.accOrderPost join nodeOrder).collapse, nodeOrder, false, true)
              } else {
                (minimalJoinConstraint, nodeOrder, true, true)
              }
            }
          }
        }

        val costModel = self.costModel.consume(nodeSize, node.ticVars, accResort, nodeResort)

        BorgTraversalPlanUnfixed(Some(self), node, nodeOrder.normalize, accOrderPost.normalize, costModel, accResort, nodeResort)
      }

      // Tic variables before the step is executed:
      lazy val preTicVars = accOrderPre.variables

      // Tic variables after the step is executed:
      lazy val postTicVars = accOrderPost.variables

      // New tic variables gained during the step:
      lazy val newTicVars = postTicVars -- preTicVars
    
      def unpack: List[BorgTraversalPlanUnfixed] = {
        def unpack0(plan: BorgTraversalPlanUnfixed, acc: List[BorgTraversalPlanUnfixed]): List[BorgTraversalPlanUnfixed] = {
          val unpackedPlan = plan

          plan.priorPlan match {
            case Some(plan0) => unpack0(plan0, unpackedPlan :: acc)
            case None => unpackedPlan :: acc
          }
        }

        unpack0(this, Nil)
      }
    }

    // 
    // Represents a (perhaps partial) traversal plan for applying the borg algorithm,
    // together with the cost of the plan.
    // 
    case class BorgTraversalPlanFixed private (node: MergeNode, resortNode: Option[Seq[TicVar]], resortAcc: Option[Seq[TicVar]], accOrderPost: Seq[TicVar], joinPrefix: Int, next: Option[BorgTraversalPlanFixed])
    object BorgTraversalPlanFixed {
      def apply(uplan: BorgTraversalPlanUnfixed): Option[BorgTraversalPlanFixed] = {


        def rec(fplan: BorgTraversalPlanFixed, uplan: BorgTraversalPlanUnfixed): BorgTraversalPlanFixed = {

        }

        uplan.priorPlan.map { priorPlan =>
          val accOrderPost = uplan.accOrderPost

          lazy val nodeFixed = uplan.nodeOrder.fixedFrom(accOrderPost - )
          lazy val accFixed = uplan.accOrder.fixed


          val (resortNode, resortAcc) = (uplan.resortNode, uplan.resortAcc) match {
            case (false, true) => 
              (Node, Some(accFixed))

            case (true, false) =>
              (Some(nodeFixed), None)

            case (true, true) =>
              (Some(nodeFixed), Some(uplan.accOrder.fixedFrom(nodeFixed)))

            case (false, false) => (None, None)
          }

          val fixed = BorgTraversalPlanFixed(
            node         = uplan.node,
            resortNode   = resortNode,
            resortAcc    = resortAcc,
            accOrderPost = uplan.accOrderPost.fixed,
            joinPrefix   = ,
            next       = None
          )

          rec(fixed, priorPlan)
        }
      }
    }

    // 
    // Finds a traversal order for the borg algorithm which minimizes the number of resorts 
    // required.
    // 
    def findBorgTraversalOrder(connectedGraph: MergeGraph, nodeOracle: MergeNode => NodeMetadata): BorgTraversalPlanUnfixed= {
      import OrderingConstraint2._

      // Find all the nodes accessible from the specified node (through edges):
      def connections(node: MergeNode): Set[MergeNode] = connectedGraph.edgesFor(node).flatMap(e => Set(e.a, e.b)) - node

      def pick(consumed: Set[MergeNode], choices: Set[MergeNode], plan: BorgTraversalPlanUnfixed): Set[BorgTraversalPlanUnfixed] = {
        if (choices.isEmpty) Set(plan)
        else for {
          choice <- choices
          finalPlan <- {
            val nodeMetadata = nodeOracle(choice)

            val consumed0 = consumed + choice
            val choices0 = (choices union connections(choice)) diff consumed0

            val newPlan = plan.consume(choice, nodeMetadata.size, nodeMetadata.nodeOrderOpt)

            pick(consumed0, choices0, newPlan)
          }
        } yield finalPlan
      }

      val initials = connectedGraph.nodes.map { node =>
        val nodeOrder = nodeOracle(node).nodeOrderOpt.map(Ordered.fromVars).getOrElse(Unordered.fromVars(node.ticVars))

        val plan = BorgTraversalPlanUnfixed(
          priorPlan    = None,
          node         = node,
          nodeOrder    = nodeOrder,
          accOrderPost = nodeOrder,
          accResort    = false,
          nodeResort   = false,
          costModel    = BorgTraversalCostModel.Zero
        )

        val choices = connections(node)

        (plan, choices)
      }
      (initials.flatMap {
        case (plan, choices) =>
          pick(Set(plan.node), choices, plan)
      }).toSeq.sortBy(_.costModel.ioCost).head
    }
    */

    def reorderGroupKeySpec(source: TransSpec1, newOrder: Seq[TicVar], original: Seq[TicVar]) = {
      OuterObjectConcat(
        newOrder.zipWithIndex.map {
          case (ticvar, i) => GroupKeyTrans.reindex(source, original.indexOf(ticvar), i)
        }: _*
      )
    }

    def resortVictimToBorgResult(victim: NodeSubset, toPrefix: Seq[TicVar]): M[BorgResult] = {
      import BorgResult._

      // the assimilator is already in a compatible ordering, so we need to
      // resort the victim and we don't care what the remainder of the ordering is,
      // because we're not smart enough to figure out what the right one is and are
      // counting on our traversal order to get the biggest ordering constraints first.
      val groupId = victim.node.binding.groupId
      val newOrder = toPrefix ++ (victim.node.keys -- toPrefix).toSeq
      val remapSpec = OuterObjectConcat(
        wrapGroupKeySpec(reorderGroupKeySpec(victim.groupKeyTrans.spec, newOrder, victim.groupKeyTrans.keyOrder)) ::
        wrapIdentSpec(nestInGroupId(victim.idTrans, groupId)) :: 
        victim.targetTrans.map(t => wrapValueSpec(nestInGroupId(t, groupId))).toList: _*
      )

      val transformed = victim.table.transform(remapSpec)
      for {
        //json <- transformed.toJson
        //_ = println("pre-sort-victim "  + victim.groupId + ": " + json.mkString("\n"))
        sorted <- transformed.sort(groupKeySpec(Source), SortAscending)
        //sjson <- sorted.toJson
        //_ = println("post-sort-victim " + victim.groupId + ": " + sjson.mkString("\n"))
      } yield {
        BorgResult(sorted, newOrder, Set(victim.node.binding.groupId), sorted = true)
      }
    }

    def join(leftNode: BorgNode, rightNode: BorgNode, requiredOrders: Map[MergeNode, Set[Seq[TicVar]]]): M[BorgResultNode] = {
      import BorgResult._
      import OrderingConstraints._

      def resortBorgResult(borgResult: BorgResult, newPrefix: Seq[TicVar]): M[BorgResult] = {
        val newAssimilatorOrder = newPrefix ++ (borgResult.groupKeys diff newPrefix)

        val remapSpec = OuterObjectConcat(
          wrapGroupKeySpec(reorderGroupKeySpec(groupKeySpec(Source), newAssimilatorOrder, borgResult.groupKeys)),
          wrapIdentSpec(identSpec(Source)),
          wrapValueSpec(valueSpec(Source))
        )

        val transformed = borgResult.table.transform(remapSpec)

        for {
          //json <- transformed.toJson
          //_ = println("pre-resort-borg\n" + json.mkString("\n"))
          sorted <- transformed.sort(groupKeySpec(Source), SortAscending) 
          //sjson <- sorted.toJson
          //_ = println("post-resort-borg\n" + sjson.mkString("\n"))
        } yield {
          borgResult.copy(table = sorted, groupKeys = newAssimilatorOrder, sorted = true)
        }
      }

      def resortBoth(assimilator: BorgResult, victim: NodeSubset): Option[M[(BorgResult, BorgResult, Seq[TicVar])]] = {
        // no compatible prefix, so both will require a resort
        // this case needs optimization to see into the future to know what the best ordering
        // is, but if every time we assimilate a node we have removed an ordering that was only
        // required to assimilate some previously consumed node, we can know that we're at least 
        // picking from an order that may be necessary for some other purpose.
        findLongestPrefix(assimilator.groupKeys.toSet, requiredOrders(victim.node)) map { newPrefix =>
          for {
            sortedAssimilator <- resortBorgResult(assimilator, newPrefix)
            sortedVictim <- resortVictimToBorgResult(victim, newPrefix)
          } yield {
            (sortedAssimilator, sortedVictim, newPrefix)
          }
        }
      }

      def joinAsymmetric(assimilator: BorgResult, victim: NodeSubset): M[(BorgResult, BorgResult, Seq[TicVar])] = {
        (if (victim.sortedByIdentities) {
          findCompatiblePrefix(Set(assimilator.groupKeys), requiredOrders(victim.node)) map { commonPrefix =>
            resortVictimToBorgResult(victim, commonPrefix) map { prepared => 
              (assimilator, prepared, commonPrefix) 
            }
          } orElse {
            resortBoth(assimilator, victim)
          } 
        } else {
          // victim already has a sort order that may or may not be compatible with the assimilator
          findCompatiblePrefix(Set(assimilator.groupKeys), Set(victim.groupKeyTrans.keyOrder)) map { commonPrefix =>
            // the assimilator and the victim are both already sorted according to a compatible
            // ordering, so we just need to transform the victim to a BorgResult
            (assimilator, BorgResult(victim), commonPrefix).point[M]
          } orElse {
            // the victim had a preexisting sort order which was incompatible with the assimilator.
            // so we should first look to see if there's a compatible prefix with one of the other
            // required sort orders for the node that the victim can be sorted by
            findCompatiblePrefix(Set(assimilator.groupKeys), requiredOrders(victim.node)) map { commonPrefix =>
              resortVictimToBorgResult(victim, commonPrefix) map { prepared => 
                (assimilator, prepared, commonPrefix)
              }
            }
          } orElse {
            // the assimilator ordering was not compatible with any of the required sort orders
            // for the node, so we need to resort the accumulator to be compatible with
            // the victim
            findLongestPrefix(assimilator.groupKeys.toSet, Set(victim.groupKeyTrans.keyOrder)) map { newPrefix =>
              resortBorgResult(assimilator, newPrefix) map { newAssimilator =>
                (newAssimilator, BorgResult(victim), newPrefix)
              }
            }
          } orElse {
            // the victim must have a sort order that is entirely incompatible with the assimilator, 
            // so both must be resorted. For example, assimilator: {a, b, c, d}, victim: [a, b, e, d]
            resortBoth(assimilator, victim)
          } 
        }) getOrElse {
          sys.error("Assimilator with keys " + assimilator.groupKeys + " and victim " + victim + " incompatible")
        }
      }

      def joinSymmetric(borgLeft: BorgResult, borgRight: BorgResult): M[(BorgResult, BorgResult, Seq[TicVar])] = {
        def resortLeft(borgLeft: BorgResult, borgRight: BorgResult): Option[M[(BorgResult, BorgResult, Seq[TicVar])]] = {
          findLongestPrefix(borgLeft.groupKeys.toSet, Set(borgRight.groupKeys)) map { commonPrefix =>
            resortBorgResult(borgLeft, commonPrefix) map { newBorgLeft =>
              (newBorgLeft, borgRight, commonPrefix)
            }
          }
        }

        def resortRight(borgLeft: BorgResult, borgRight: BorgResult): Option[M[(BorgResult, BorgResult, Seq[TicVar])]] = {
          findLongestPrefix(borgRight.groupKeys.toSet, Set(borgLeft.groupKeys)) map { commonPrefix =>
            resortBorgResult(borgRight, commonPrefix) map { newBorgRight =>
              (borgLeft, newBorgRight, commonPrefix)
            }
          }
        }

        findCompatiblePrefix(Set(borgLeft.groupKeys), Set(borgRight.groupKeys)) map { compatiblePrefix =>
          (borgLeft, borgRight, compatiblePrefix).point[M]
        } orElse {
          // one or the other will require a re-sort
          if (borgLeft.size lessThan borgRight.size) {
            resortLeft(borgLeft, borgRight) orElse
            // Well, that didn't work, so let's at least attempt the reverse constraints
            resortRight(borgLeft, borgRight)
          } else {
            resortRight(borgLeft, borgRight) orElse
            // Well, that didn't work, so let's at least attempt the reverse constraints
            resortLeft(borgLeft, borgRight)
          }
        } getOrElse {
          sys.error("Borged sets sharing an edge are incompatible: " + borgLeft.groupKeys + "; " + borgRight.groupKeys)
        }
      }

      if (leftNode == rightNode) {
        leftNode match {
          case leftResult : BorgResultNode => leftResult.point[M]
          case _ => sys.error("Cannot have a self-cycle on non-result BorgNodes")
        }
      } else {
        val joinablesM = (leftNode, rightNode) match {
          case (BorgVictimNode(left), BorgVictimNode(right)) =>
            if (left.sortedByIdentities && right.sortedByIdentities) {
              // choose compatible ordering for both sides from requiredOrders
              OrderingConstraints.findCompatiblePrefix(requiredOrders(left.node), requiredOrders(right.node)) match {
                case Some(prefix) =>
                  val (toSort, toAssimilate) = if (left.size lessThan right.size) (left, right) 
                                               else (right, left)

                  resortVictimToBorgResult(toSort, prefix) flatMap { assimilator =>
                    joinAsymmetric(assimilator, toAssimilate)
                  }

                case None =>
                  sys.error("Unable to find compatible ordering from minimized set; something went wrong.")
              }
            } else if (left.sortedByIdentities) {
              joinAsymmetric(BorgResult(right), left)
            } else if (right.sortedByIdentities) {
              joinAsymmetric(BorgResult(left), right)
            } else {
              // TODO: joinSymmetric needs a "resortBoth" case to handle this correctly.
              joinAsymmetric(BorgResult(left), right)
            }

          case (BorgResultNode(left), BorgVictimNode(right)) => joinAsymmetric(left, right)
          case (BorgVictimNode(left), BorgResultNode(right)) => joinAsymmetric(right, left)
          case (BorgResultNode(left), BorgResultNode(right)) => joinSymmetric(left, right)
        }

        joinablesM map {
          case (leftJoinable, rightJoinable, commonPrefix) =>

            // todo: Reorder keys into the order specified by the common prefix. The common prefix determination
            // will be factored out in the plan-based version, and will simply be a specified keyOrder that both
            // sides will be brought into alignment with, instead of passing in the required sort orders.
            val neededRight = rightJoinable.groupKeys diff leftJoinable.groupKeys
            val rightKeyMap = rightJoinable.groupKeys.zipWithIndex.toMap

            val groupKeyTrans2 = wrapGroupKeySpec(
              OuterObjectConcat(
                groupKeySpec(SourceLeft) +: neededRight.zipWithIndex.map { 
                  case (key, i) =>
                    WrapObject(
                      DerefObjectStatic(groupKeySpec(SourceRight), CPathField(GroupKeyTrans.keyName(rightKeyMap(key)))),
                      GroupKeyTrans.keyName(i + leftJoinable.groupKeys.size)
                    )
                }: _*
              )
            )

            val cogroupBySpec = OuterObjectConcat(
              (0 until commonPrefix.size) map { i =>
                WrapObject(
                  DerefObjectStatic(groupKeySpec(Source), CPathField(GroupKeyTrans.keyName(i))),
                  GroupKeyTrans.keyName(i)
                )
              }: _*
            )

            val idTrans2 = wrapIdentSpec(OuterObjectConcat(identSpec(SourceLeft), identSpec(SourceRight)))
            val recordTrans2 = wrapValueSpec(OuterObjectConcat(valueSpec(SourceLeft), valueSpec(SourceRight)))

            val borgTrans = OuterObjectConcat(groupKeyTrans2, idTrans2, recordTrans2)
            val unmatchedTrans = ObjectDelete(Leaf(Source), BorgResult.allFields)

            val cogrouped = leftJoinable.table.cogroup(cogroupBySpec, cogroupBySpec, rightJoinable.table)(unmatchedTrans, unmatchedTrans, borgTrans)

            BorgResultNode(
              BorgResult(
                cogrouped,
                leftJoinable.groupKeys ++ neededRight,
                leftJoinable.groups union rightJoinable.groups,
                UnknownSize,
                sorted = false
              )
            )
        }
      }
    }

    sealed trait BorgNode {
      def keys: Seq[TicVar]
    }

    case class BorgResultNode(result: BorgResult) extends BorgNode {
      def keys = result.groupKeys
    }

    case class BorgVictimNode(independent: NodeSubset) extends BorgNode {
      def keys = independent.groupKeyTrans.keyOrder
    }

    case class BorgEdge(left: BorgNode, right: BorgNode) {
      def sharedKeys = left.keys intersect right.keys
    }

    /* Take the distinctiveness of each node (in terms of group keys) and add it to the uber-cogrouped-all-knowing borgset */
    def borg(spanningGraph: MergeGraph, connectedSubgraph: Set[NodeSubset], requiredOrders: Map[MergeNode, Set[Seq[TicVar]]]): M[BorgResult] = {
      def assimilate(edges: Set[BorgEdge]): M[BorgResult] = {
        val largestEdge = edges.maxBy(_.sharedKeys.size)

        //val prunedRequirements = orderingIndex.foldLeft(Map.empty[MergeNode, Set[Seq[TicVar]]]) {
        //  case (acc, (key, nodes)) => nodes.foldLeft(acc) {
        //    case (acc, node) => acc + (node -> (acc.getOrElse(node, Set()) + key))
        //  }
        //}

        for {
          assimilated <- join(largestEdge.left, largestEdge.right, requiredOrders)
          result <- {
            val edges0 = (edges - largestEdge) map {
              case BorgEdge(a, b) if a == largestEdge.left || a == largestEdge.right => BorgEdge(assimilated, b) 
              case BorgEdge(a, b) if b == largestEdge.left || b == largestEdge.right => BorgEdge(a, assimilated) 
              case other => other
            }

            if (edges0.isEmpty) assimilated.result.point[M] else assimilate(edges0)
          }
        } yield result
      }

      assert(connectedSubgraph.nonEmpty)
      if (connectedSubgraph.size == 1) {
        val victim = connectedSubgraph.head
        resortVictimToBorgResult(victim, victim.groupKeyTrans.keyOrder)
      } else {
        val borgNodes = connectedSubgraph.map(BorgVictimNode.apply)
        val victims: Map[MergeNode, BorgNode] = borgNodes.map(s => s.independent.node -> s).toMap
        val borgEdges = spanningGraph.edges.map {
          case MergeEdge(a, b) => BorgEdge(victims(a), victims(b))
        }

        //val orderingIndex = requiredOrders.foldLeft(Map.empty[Seq[TicVar], Set[MergeNode]])  {
        //  case (acc, (orderings, node)) => 
        //    orderings.foldLeft(acc) { 
        //      case (acc, o) => acc + (o -> (acc.getOrElse(o, Set()) + node))
        //    }
        //}
        assimilate(borgEdges)
      }
    }

    def crossAllTrans(leftKeySize: Int, rightKeySize: Int): TransSpec2 = {
      import BorgResult._
      val keyTransLeft = groupKeySpec(SourceLeft)

      // remap the group keys on the right so that they don't conflict with the left
      // and respect the composite ordering of the left and right
      val keyTransRight = OuterObjectConcat(
        (0 until rightKeySize) map { i =>
          GroupKeyTrans.reindex(groupKeySpec(SourceRight), i, i + leftKeySize)
        }: _*
      )

      val groupKeyTrans2 = wrapGroupKeySpec(OuterObjectConcat(keyTransLeft, keyTransRight))
      val idTrans2 = wrapIdentSpec(OuterObjectConcat(identSpec(SourceLeft), identSpec(SourceRight)))
      val recordTrans2 = wrapValueSpec(OuterObjectConcat(valueSpec(SourceLeft), valueSpec(SourceRight)))

      OuterObjectConcat(groupKeyTrans2, idTrans2, recordTrans2)
    }

    def crossAll(borgResults: Set[BorgResult]): BorgResult = {
      import TransSpec._
      def cross2(left: BorgResult, right: BorgResult): BorgResult = {
        val omniverseTrans = crossAllTrans(left.groupKeys.size, right.groupKeys.size)

        BorgResult(left.table.cross(right.table)(omniverseTrans),
                   left.groupKeys ++ right.groupKeys,
                   left.groups ++ right.groups,
                   UnknownSize, sorted = false)
      }

      borgResults.reduceLeft(cross2)
    }

    // Create the omniverse
    def unionAll(borgResults: Set[BorgResult]): BorgResult = {
      import TransSpec._

      def union2(left: BorgResult, right: BorgResult): BorgResult = {
        // At the point that we union, both sides should have the same ticvars, although they
        // may not be in the same order. If a reorder is needed, we'll have to remap since
        // groupKeys are identified by index.
        val (rightRemapped, groupKeys) = if (left.groupKeys == right.groupKeys) {
          (right.table, left.groupKeys)
        } else {
          val extraRight = (right.groupKeys diff left.groupKeys).toArray
          val leftKeys = left.groupKeys.toArray
          val rightKeys = right.groupKeys.zipWithIndex

          val remapKeyTrans = OuterObjectConcat((
            // Remap the keys that exist on the left into the proper order
            ((0 until leftKeys.length) flatMap { leftIndex: Int =>
              rightKeys.find(_._1 == leftKeys(leftIndex)).map {
                case (_, rightIndex) => {
                  GroupKeyTrans.reindex(
                    DerefObjectStatic(Leaf(Source), CPathField("groupKeys")),
                    rightIndex,
                    leftIndex
                  )
                }
              }
            }) ++
            // Remap the extra keys from the right, if any
            ((0 until extraRight.length) map { extraIndex: Int =>
              rightKeys.find(_._1 == extraRight(extraIndex)).map {
                case (_, rightIndex) => {
                  GroupKeyTrans.reindex(
                    DerefObjectStatic(Leaf(Source), CPathField("groupKeys")),
                    rightIndex,
                    leftKeys.length + extraIndex
                  )
                }
              }.get
            })
          ): _*)

          val remapFullSpec = OuterObjectConcat(
            WrapObject(remapKeyTrans, "groupKeys"),
            WrapObject(DerefObjectStatic(Leaf(Source), CPathField("identities")), "identities"),
            WrapObject(DerefObjectStatic(Leaf(Source), CPathField("values")), "values")
          )

          (right.table.transform(remapFullSpec), left.groupKeys ++ extraRight.toSeq)
        }

        BorgResult(Table(left.table.slices ++ rightRemapped.slices, UnknownSize),
                   groupKeys,
                   left.groups ++ right.groups,
                   UnknownSize, sorted = false)
      }

      borgResults.reduceLeft(union2)
    }

    /**
     * Merge controls the iteration over the table of group key values. 
     */
    def merge(grouping: GroupingSpec)(body: (Table, GroupId => M[Table]) => M[Table]): M[Table] = {
      import BorgResult._

      // all of the universes will be unioned together.
      val universes = findBindingUniverses(grouping)
      val borgedUniverses: M[Stream[BorgResult]] = universes.toStream.map { universe =>
        val alignedSpanningGraphsM: M[Set[(MergeGraph, Map[MergeNode, Set[Seq[TicVar]]], Map[GroupId, Set[NodeSubset]])]] = 
          universe.spanningGraphs.map { spanningGraph =>
            // Compute required sort orders based on graph traversal
            val requiredSorts: Map[MergeNode, Set[Seq[TicVar]]] = findRequiredSorts(spanningGraph)
            for (aligned <- alignOnEdges(spanningGraph, requiredSorts))
              yield (spanningGraph, requiredSorts, aligned)
          }.sequence

        val minimizedSpanningGraphsM: M[Set[(MergeGraph, Set[NodeSubset], Map[MergeNode, Set[Seq[TicVar]]])]] = for {
          aligned      <- alignedSpanningGraphsM
          intersected  <- aligned.map { 
                            case (spanningGraph, requiredSorts, alignment) => 
                              for (intersected <- alignment.values.toStream.map(intersect(_, requiredSorts)).sequence)
                                yield (spanningGraph, intersected.toSet, requiredSorts)
                          }.sequence
          //_ = println("intersected: " + System.currentTimeMillis)
        } yield intersected

        for {
          spanningGraphs <- minimizedSpanningGraphsM
          //_ = println("spanned: " + System.currentTimeMillis)
          borgedGraphs <- spanningGraphs.map(Function.tupled(borg)).sequence
          //_ = println("borged: " + System.currentTimeMillis)
          // cross all of the disconnected subgraphs within a single universe
          crossed = crossAll(borgedGraphs)
          //json <- crossed.table.toJson
          //_ = println("crossed universe: " + json.toList)
          //_ = println("crossed: " + System.currentTimeMillis)
        } yield crossed
      }.sequence

      for {
        omniverse <- borgedUniverses.map(s => unionAll(s.toSet))
        //json <- omniverse.table.toJson
        //_ = println("omniverse: \n" + json.mkString("\n"))
        sorted <- if (omniverse.sorted) {
            M.point(omniverse.table)
          } else {
            omniverse.table.sort(groupKeySpec(Source))
          }
        //sorted <- omniverse.table.compact(groupKeySpec(Source)).sort(groupKeySpec(Source))
        result <- sorted.partitionMerge(DerefObjectStatic(Leaf(Source), CPathField("groupKeys"))) { partition =>
          val groupKeyTrans = OuterObjectConcat(
            omniverse.groupKeys.zipWithIndex map { case (ticvar, i) =>
              WrapObject(
                DerefObjectStatic(groupKeySpec(Source), CPathField(GroupKeyTrans.keyName(i))),
                ticvar.name
              )
            } : _*
          )

          val groups: M[Map[GroupId, Table]] = 
            for {
              grouped <- omniverse.groups.map { groupId =>
                           val recordTrans = OuterObjectConcat(
                             WrapObject(DerefObjectStatic(identSpec(Source), CPathField(groupId.shows)), "0"),
                             WrapObject(DerefObjectStatic(valueSpec(Source), CPathField(groupId.shows)), "1")
                           )

                           val sortByTrans = DerefObjectStatic(Leaf(Source), CPathField("0"))
                           // transform to get just the information related to the particular groupId,
                           for {
                             partitionSorted <- partition.transform(recordTrans).sort(sortByTrans, unique = true)
                             //json <- partitionSorted.toJson
                             //_ = println("group " + groupId + " partition: " + json.mkString("\n"))
                           } yield {
                             groupId -> partitionSorted.transform(DerefObjectStatic(Leaf(Source), CPathField("1")))
                           }
                         }.sequence
            } yield grouped.toMap

          val groupKeyForBody = partition.takeRange(0, 1).transform(groupKeyTrans) 
          body(
            groupKeyForBody,
            (groupId: GroupId) => for {
              //groupIdJson <- groupKeyForBody.toJson
              groupTable <- groups.map(_(groupId))
            } yield groupTable
          )
        }
      } yield result
    }
  }

  abstract class ColumnarTable(slices0: StreamT[M, Slice], val size: TableSize) extends TableLike { self: Table =>
    import SliceTransform._

    private final val readStarts = new java.util.concurrent.atomic.AtomicInteger
    private final val blockReads = new java.util.concurrent.atomic.AtomicInteger

    val slices = StreamT(
      StreamT.Skip({
        readStarts.getAndIncrement
        slices0.map(s => { blockReads.getAndIncrement; s })
      }).point[M]
    )

    /**
     * Folds over the table to produce a single value (stored in a singleton table).
     */

    def reduce[A](reducer: Reducer[A])(implicit monoid: Monoid[A]): M[A] = {  
      def rec(stream: StreamT[M, A], acc: A): M[A] = {
        stream.uncons flatMap {
          case Some((head, tail)) => rec(tail, head |+| acc) 
          case None => M.point(acc)
        }    
      }    

      rec(slices map { s => reducer.reduce(s.logicalColumns, 0 until s.size) }, monoid.zero)
    }    

    def compact(spec: TransSpec1): Table = {
      val specTransform = SliceTransform.composeSliceTransform(spec)
      val compactTransform = SliceTransform.composeSliceTransform(Leaf(Source)).zip(specTransform) { (s1, s2) => s1.compact(s2, AnyDefined) }
      Table(Table.transformStream(compactTransform, slices), size).normalize
    }

    /**
     * Performs a one-pass transformation of the keys and values in the table.
     * If the key transform is not identity, the resulting table will have
     * unknown sort order.
     */
    def transform(spec: TransSpec1): Table = {
      Table(Table.transformStream(composeSliceTransform(spec), slices), this.size)
    }
    
    def force: M[Table] = this.sort(Scan(Leaf(Source), freshIdScanner), SortAscending) //, unique = true)
    
    def paged(limit: Int): Table = {
      val slices2 = slices flatMap { slice =>
        StreamT.unfoldM(0) { idx =>
          val back = if (idx >= slice.size)
            None
          else
            Some((slice.takeRange(idx, limit), idx + limit))
          
          M.point(back)
        }
      }
      
      Table(slices2, size)
    }

    /**
     * Cogroups this table with another table, using equality on the specified
     * transformation on rows of the table.
     */
    def cogroup(leftKey: TransSpec1, rightKey: TransSpec1, that: Table)(leftResultTrans: TransSpec1, rightResultTrans: TransSpec1, bothResultTrans: TransSpec2): Table = {
      //println("Cogrouping with respect to\nleftKey: " + leftKey + "\nrightKey: " + rightKey)
      class IndexBuffers(lInitialSize: Int, rInitialSize: Int) {
        val lbuf = new ArrayIntList(lInitialSize)
        val rbuf = new ArrayIntList(rInitialSize)
        val leqbuf = new ArrayIntList(lInitialSize max rInitialSize)
        val reqbuf = new ArrayIntList(lInitialSize max rInitialSize)

        @inline def advanceLeft(lpos: Int): Unit = {
          lbuf.add(lpos)
          rbuf.add(-1)
          leqbuf.add(-1)
          reqbuf.add(-1)
        }

        @inline def advanceRight(rpos: Int): Unit = {
          lbuf.add(-1)
          rbuf.add(rpos)
          leqbuf.add(-1)
          reqbuf.add(-1)
        }

        @inline def advanceBoth(lpos: Int, rpos: Int): Unit = {
          lbuf.add(-1)
          rbuf.add(-1)
          leqbuf.add(lpos)
          reqbuf.add(rpos)
        }

        def cogrouped[LR, RR, BR](lslice: Slice, 
                                  rslice: Slice, 
                                  leftTransform:  SliceTransform1[LR], 
                                  rightTransform: SliceTransform1[RR], 
                                  bothTransform:  SliceTransform2[BR]): (Slice, LR, RR, BR) = {

          val remappedLeft = lslice.remap(lbuf)
          val remappedRight = rslice.remap(rbuf)

          val remappedLeq = lslice.remap(leqbuf)
          val remappedReq = rslice.remap(reqbuf)

          val (ls0, lx) = leftTransform(remappedLeft)
          val (rs0, rx) = rightTransform(remappedRight)
          val (bs0, bx) = bothTransform(remappedLeq, remappedReq)

          assert(lx.size == rx.size && rx.size == bx.size)
          val resultSlice = lx zip rx zip bx

          (resultSlice, ls0, rs0, bs0)
        }

        override def toString = {
          "left: " + lbuf.toArray.mkString("[", ",", "]") + "\n" + 
          "right: " + rbuf.toArray.mkString("[", ",", "]") + "\n" + 
          "both: " + (leqbuf.toArray zip reqbuf.toArray).mkString("[", ",", "]")
        }
      }

      case class SlicePosition[K](
        /** The position in the current slice. This will only be nonzero when the slice has been appended
         * to as a result of a cartesian crossing the slice boundary */
        pos: Int,
        /** Present if not in a final right or left run. A pair of a key slice that is parallel to the
         * current data slice, and the value that is needed as input to sltk or srtk to produce the next key. */
        keyState: K,
        key: Slice,
        /** The current slice to be operated upon. */
        data: Slice,
        /** The remainder of the stream to be operated upon. */
        tail: StreamT[M, Slice])

      sealed trait NextStep[A, B]
      case class SplitLeft[A, B](lpos: Int) extends NextStep[A, B]
      case class SplitRight[A, B](rpos: Int) extends NextStep[A, B]
      case class NextCartesianLeft[A, B](left: SlicePosition[A], right: SlicePosition[B], rightStart: Option[SlicePosition[B]], rightEnd: Option[SlicePosition[B]]) extends NextStep[A, B]
      case class NextCartesianRight[A, B](left: SlicePosition[A], right: SlicePosition[B], rightStart: Option[SlicePosition[B]], rightEnd: Option[SlicePosition[B]]) extends NextStep[A, B]
      case class SkipRight[A, B](left: SlicePosition[A], rightEnd: SlicePosition[B]) extends NextStep[A, B]
      case class RestartRight[A, B](left: SlicePosition[A], rightStart: SlicePosition[B], rightEnd: SlicePosition[B]) extends NextStep[A, B]
      def cogroup0[LK, RK, LR, RR, BR](stlk: SliceTransform1[LK], strk: SliceTransform1[RK], stlr: SliceTransform1[LR], strr: SliceTransform1[RR], stbr: SliceTransform2[BR]) = {

        sealed trait CogroupState
        case class EndLeft(lr: LR, lhead: Slice, ltail: StreamT[M, Slice]) extends CogroupState
        case class Cogroup(lr: LR, rr: RR, br: BR, left: SlicePosition[LK], right: SlicePosition[RK], rightStart: Option[SlicePosition[RK]], rightEnd: Option[SlicePosition[RK]]) extends CogroupState
        case class EndRight(rr: RR, rhead: Slice, rtail: StreamT[M, Slice]) extends CogroupState
        case object CogroupDone extends CogroupState

        val Reset = -1
        
        // step is the continuation function fed to uncons. It is called once for each emitted slice
        def step(state: CogroupState): M[Option[(Slice, CogroupState)]] = {

          // step0 is the inner monadic recursion needed to cross slice boundaries within the emission of a slice
          def step0(lr: LR, rr: RR, br: BR, leftPosition: SlicePosition[LK], rightPosition: SlicePosition[RK], rightStart0: Option[SlicePosition[RK]], rightEnd0: Option[SlicePosition[RK]])
                   (ibufs: IndexBuffers = new IndexBuffers(leftPosition.key.size, rightPosition.key.size)): M[Option[(Slice, CogroupState)]] = {

            val SlicePosition(lpos0, lkstate, lkey, lhead, ltail) = leftPosition
            val SlicePosition(rpos0, rkstate, rkey, rhead, rtail) = rightPosition

            val comparator = Slice.rowComparatorFor(lkey, rkey) {
              // since we've used the key transforms, and since transforms are contracturally
              // forbidden from changing slice size, we can just use all
              _.columns.keys map (_.selector)
            }

            // the inner tight loop; this will recur while we're within the bounds of
            // a pair of slices. Any operation that must cross slice boundaries
            // must exit this inner loop and recur through the outer monadic loop
            // xrstart is an int with sentinel value for effieiency, but is Option at the slice level.
            @inline @tailrec def buildRemappings(lpos: Int, rpos: Int, rightStart: Option[SlicePosition[RK]], rightEnd: Option[SlicePosition[RK]], endRight: Boolean): NextStep[LK, RK] = {
              //println("lpos = %d, rpos = %d, rightStart = %s, rightEnd = %s, endRight = %s" format (lpos, rpos, rightStart, rightEnd, endRight))
              //println("Left key: " + lkey.toJson(lpos))
              //println("Right key: " + rkey.toJson(rpos))
              //println("Left data: " + lhead.toJson(lpos))
              //println("Right data: " + rhead.toJson(rpos))

              rightStart match {
                case Some(resetMarker @ SlicePosition(rightStartPos, _, rightStartSlice, _, _)) =>
                  // We're currently in a cartesian.
                  if (lpos < lhead.size && rpos < rhead.size) {
                    comparator.compare(lpos, rpos) match {
                      case LT if rightStartSlice == rkey =>
                        buildRemappings(lpos + 1, rightStartPos, rightStart, Some(rightPosition.copy(pos = rpos)), endRight)
                      case LT =>
                        // Transition to emit the current slice and reset the right side, carry rightPosition through
                        RestartRight(leftPosition.copy(pos = lpos + 1), resetMarker, rightPosition.copy(pos = rpos))
                      case GT =>
                        // catch input-out-of-order errors early
                        rightEnd match {
                          case None =>
                            //println("lhead\n" + lhead.toJsonString())
                            //println("rhead\n" + rhead.toJsonString())
                            sys.error("Inputs are not sorted; value on the left exceeded value on the right at the end of equal span. lpos = %d, rpos = %d".format(lpos, rpos))

                          case Some(SlicePosition(endPos, _, endSlice, _, _)) if endSlice == rkey =>
                            buildRemappings(lpos, endPos, None, None, endRight)

                          case Some(rend @ SlicePosition(_, _, _, _, _)) =>
                            // Step out of buildRemappings so that we can restart with the current rightEnd
                            SkipRight(leftPosition.copy(pos = lpos), rend)
                        }
                      case EQ =>
                        ibufs.advanceBoth(lpos, rpos)
                        buildRemappings(lpos, rpos + 1, rightStart, rightEnd, endRight)
                    }
                  } else if (lpos < lhead.size) {
                    if (rightStartSlice == rkey) {
                      // we know there won't be another slice on the RHS, so just keep going to exhaust the left
                      buildRemappings(lpos + 1, rightStartPos, rightStart, Some(rightPosition.copy(pos = rpos)), endRight)
                    } else if (endRight) {
                      RestartRight(leftPosition.copy(pos = lpos + 1), resetMarker, rightPosition.copy(pos = rpos))
                    } else {
                      // right slice is exhausted, so we need to emit that slice from the right tail
                      // then continue in the cartesian
                      NextCartesianRight(leftPosition.copy(pos = lpos + 1), rightPosition, rightStart, rightEnd)
                    }
                  } else if (rpos < rhead.size) {
                    // left slice is exhausted, so we need to emit that slice from the left tail
                    // then continue in the cartesian
                    NextCartesianLeft(leftPosition, rightPosition.copy(pos = rpos), rightStart, rightEnd)
                  } else {
                    sys.error("This state should be unreachable, since we only increment one side at a time.")
                  }

                case None =>
                  // not currently in a cartesian, hence we can simply proceed.
                  if (lpos < lhead.size && rpos < rhead.size) {
                    comparator.compare(lpos, rpos) match {
                      case LT =>
                        ibufs.advanceLeft(lpos)
                        buildRemappings(lpos + 1, rpos, None, None, endRight)
                      case GT =>
                        ibufs.advanceRight(rpos)
                        buildRemappings(lpos, rpos + 1, None, None, endRight)
                      case EQ =>
                        ibufs.advanceBoth(lpos, rpos)
                        buildRemappings(lpos, rpos + 1, Some(rightPosition.copy(pos = rpos)), None, endRight)
                    }
                  } else if (lpos < lhead.size) {
                    // right side is exhausted, so we should just split the left and emit
                    SplitLeft(lpos)
                  } else if (rpos < rhead.size) {
                    // left side is exhausted, so we should just split the right and emit
                    SplitRight(rpos)
                  } else {
                    sys.error("This state should be unreachable, since we only increment one side at a time.")
                  }
              }
            }

            def continue(nextStep: NextStep[LK, RK]): M[Option[(Slice, CogroupState)]] = nextStep match {
              case SplitLeft(lpos) =>

                val (lpref, lsuf) = lhead.split(lpos)
                val (_, lksuf) = lkey.split(lpos)
                val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(lpref, rhead, 
                                                                     SliceTransform1[LR](lr, stlr.f),
                                                                     SliceTransform1[RR](rr, strr.f),
                                                                     SliceTransform2[BR](br, stbr.f))

                rtail.uncons map {
                  case Some((nextRightHead, nextRightTail)) => 
                    val (rkstate0, rkey0) = strk.f(rkstate, nextRightHead)
                    val nextState = Cogroup(lr0, rr0, br0, 
                                            SlicePosition(0, lkstate,  lksuf, lsuf, ltail),
                                            SlicePosition(0, rkstate0, rkey0, nextRightHead, nextRightTail),
                                            None, None)

                    Some(completeSlice -> nextState)

                  case None => 
                    val nextState = EndLeft(lr0, lsuf, ltail)
                    Some(completeSlice -> nextState)
                }

              case SplitRight(rpos) => 

                val (rpref, rsuf) = rhead.split(rpos)
                val (_, rksuf) = rkey.split(rpos)
                val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(lhead, rpref, 
                                                                     SliceTransform1[LR](lr, stlr.f),
                                                                     SliceTransform1[RR](rr, strr.f),
                                                                     SliceTransform2[BR](br, stbr.f))


                ltail.uncons map {
                  case Some((nextLeftHead, nextLeftTail)) =>
                    val (lkstate0, lkey0) = stlk.f(lkstate, nextLeftHead)
                    val nextState = Cogroup(lr0, rr0, br0,
                                            SlicePosition(0, lkstate0, lkey0, nextLeftHead, nextLeftTail),
                                            SlicePosition(0, rkstate,  rksuf, rsuf, rtail),
                                            None, None)

                    Some(completeSlice -> nextState)

                  case None =>
                    val nextState = EndRight(rr0, rsuf, rtail)
                    Some(completeSlice -> nextState)
                }

              case NextCartesianLeft(left, right, rightStart, rightEnd) =>
                left.tail.uncons.map {
                  case Some((nextLeftHead, nextLeftTail)) =>
                    val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(left.data, right.data,
                                                                         SliceTransform1[LR](lr, stlr.f),
                                                                         SliceTransform1[RR](rr, strr.f),
                                                                         SliceTransform2[BR](br, stbr.f))

                    val (lkstate0, lkey0) = stlk.f(lkstate, nextLeftHead)
                    val nextState = Cogroup(lr0, rr0, br0,
                          SlicePosition(0, lkstate0, lkey0, nextLeftHead, nextLeftTail),
                          right,
                          rightStart, rightEnd)

                    Some(completeSlice -> nextState)

                  case None =>
                    (rightStart, rightEnd) match {
                      case (Some(_), Some(end)) =>
                        val (rpref, rsuf) = right.data.split(end.pos)
                        val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(left.data, rpref,
                                                                             SliceTransform1[LR](lr, stlr.f),
                                                                             SliceTransform1[RR](rr, strr.f),
                                                                             SliceTransform2[BR](br, stbr.f))

                        val nextState = EndRight(rr0, rsuf, right.tail)
                        Some(completeSlice -> nextState)

                      case _ =>
                        val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(left.data, right.data,
                                                                             SliceTransform1[LR](lr, stlr.f),
                                                                             SliceTransform1[RR](rr, strr.f),
                                                                             SliceTransform2[BR](br, stbr.f))

                        Some(completeSlice -> CogroupDone)
                    }
                }

              case NextCartesianRight(left, right, rightStart, rightEnd) =>
                right.tail.uncons.flatMap {
                  case Some((nextRightHead, nextRightTail)) =>
                    val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(left.data, right.data,
                                                                         SliceTransform1[LR](lr, stlr.f),
                                                                         SliceTransform1[RR](rr, strr.f),
                                                                         SliceTransform2[BR](br, stbr.f))

                    val (rkstate0, rkey0) = strk.f(rkstate, nextRightHead)
                    val nextState = Cogroup(lr0, rr0, br0,
                          left,
                          SlicePosition(0, rkstate0, rkey0, nextRightHead, nextRightTail),
                          rightStart, rightEnd)

                    M.point(Some(completeSlice -> nextState))

                  case None =>
                    continue(buildRemappings(left.pos, right.pos, rightStart, rightEnd, true))
                }

              case SkipRight(left, rightEnd) =>
                continue(buildRemappings(left.pos, rightPosition.pos, rightStart0, Some(rightEnd), true))

              case RestartRight(left, rightStart, rightEnd) =>
                val (completeSlice, lr0, rr0, br0) = ibufs.cogrouped(left.data, rightPosition.data,
                                                                     SliceTransform1[LR](lr, stlr.f),
                                                                     SliceTransform1[RR](rr, strr.f),
                                                                     SliceTransform2[BR](br, stbr.f))

                val nextState = Cogroup(lr0, rr0, br0,
                                        left,
                                        rightPosition,
                                        Some(rightStart), Some(rightEnd))

                M.point(Some(completeSlice -> nextState))
            }

            continue(buildRemappings(lpos0, rpos0, rightStart0, rightEnd0, false))
          } // end of step0 

          state match {
            case EndLeft(lr, data, tail) =>
              val (lr0, leftResult) = stlr.f(lr, data)
              tail.uncons map { unconsed =>
                Some(leftResult -> (unconsed map { case (nhead, ntail) => EndLeft(lr0, nhead, ntail) } getOrElse CogroupDone))
              }

            case Cogroup(lr, rr, br, left, right, rightReset, rightEnd) =>
              step0(lr, rr, br, left, right, rightReset, rightEnd)()

            case EndRight(rr, data, tail) =>
              val (rr0, rightResult) = strr.f(rr, data)
              tail.uncons map { unconsed =>
                Some(rightResult -> (unconsed map { case (nhead, ntail) => EndRight(rr0, nhead, ntail) } getOrElse CogroupDone))
              }

            case CogroupDone => M.point(None)
          }
        } // end of step

        val initialState = for {
          // We have to compact both sides to avoid any rows for which the key is completely undefined
          leftUnconsed  <- self.compact(leftKey).slices.uncons
          rightUnconsed <- that.compact(rightKey).slices.uncons
        } yield {
          val cogroup = for {
            (leftHead, leftTail)   <- leftUnconsed
            (rightHead, rightTail) <- rightUnconsed
          } yield {
            val (lkstate, lkey) = stlk(leftHead)
            val (rkstate, rkey) = strk(rightHead)
            Cogroup(stlr.initial, strr.initial, stbr.initial, 
                    SlicePosition(0, lkstate, lkey, leftHead,  leftTail), 
                    SlicePosition(0, rkstate, rkey, rightHead, rightTail),
                    None, None)
          } 

          cogroup orElse {
            leftUnconsed map {
              case (head, tail) => EndLeft(stlr.initial, head, tail)
            }
          } orElse {
            rightUnconsed map {
              case (head, tail) => EndRight(strr.initial, head, tail)
            }
          }
        }

        Table(StreamT.wrapEffect(initialState map { state => StreamT.unfoldM[M, Slice, CogroupState](state getOrElse CogroupDone)(step) }), UnknownSize)
      }

      cogroup0(composeSliceTransform(leftKey), 
               composeSliceTransform(rightKey), 
               composeSliceTransform(leftResultTrans), 
               composeSliceTransform(rightResultTrans), 
               composeSliceTransform2(bothResultTrans))
    }

    /**
     * Performs a full cartesian cross on this table with the specified table,
     * applying the specified transformation to merge the two tables into
     * a single table.
     */
    def cross(that: Table)(spec: TransSpec2): Table = {
      def cross0[A](transform: SliceTransform2[A]): M[StreamT[M, Slice]] = {
        case class CrossState(a: A, position: Int, tail: StreamT[M, Slice])

        def crossLeftSingle(lhead: Slice, right: StreamT[M, Slice]): StreamT[M, Slice] = {
          def step(state: CrossState): M[Option[(Slice, CrossState)]] = {
            if (state.position < lhead.size) {
              state.tail.uncons flatMap {
                case Some((rhead, rtail0)) =>
                  val lslice = new Slice {
                    val size = rhead.size
                    val columns = lhead.columns.lazyMapValues(Remap(i => state.position)(_).get)
                  }

                  val (a0, resultSlice) = transform.f(state.a, lslice, rhead)
                  M.point(Some((resultSlice, CrossState(a0, state.position, rtail0))))
                  
                case None => 
                  step(CrossState(state.a, state.position + 1, right))
              }
            } else {
              M.point(None)
            }
          }

          StreamT.unfoldM(CrossState(transform.initial, 0, right))(step _)
        }
        
        def crossRightSingle(left: StreamT[M, Slice], rhead: Slice): StreamT[M, Slice] = {
          def step(state: CrossState): M[Option[(Slice, CrossState)]] = {
            state.tail.uncons map {
              case Some((lhead, ltail0)) =>
                val lslice = new Slice {
                  val size = rhead.size * lhead.size
                  val columns = if (rhead.size == 0)
                    lhead.columns.lazyMapValues(Empty(_).get)
                  else
                    lhead.columns.lazyMapValues(Remap(_ / rhead.size)(_).get)
                }

                val rslice = new Slice {
                  val size = rhead.size * lhead.size
                  val columns = if (rhead.size == 0)
                    rhead.columns.lazyMapValues(Empty(_).get)
                  else
                    rhead.columns.lazyMapValues(Remap(_ % rhead.size)(_).get)
                }

                val (a0, resultSlice) = transform.f(state.a, lslice, rslice)
                Some((resultSlice, CrossState(a0, state.position, ltail0)))
                
              case None => None
            }
          }

          StreamT.unfoldM(CrossState(transform.initial, 0, left))(step _)
        }

        def crossBoth(ltail: StreamT[M, Slice], rtail: StreamT[M, Slice]): StreamT[M, Slice] = {
          ltail.flatMap(crossLeftSingle(_ :Slice, rtail))
        }

        this.slices.uncons flatMap {
          case Some((lhead, ltail)) =>
            that.slices.uncons flatMap {
              case Some((rhead, rtail)) =>
                for {
                  lempty <- ltail.isEmpty //TODO: Scalaz result here is negated from what it should be!
                  rempty <- rtail.isEmpty
                } yield {
                  val frontSize = lhead.size * rhead.size
                  
                  if (lempty && frontSize <= yggConfig.maxSliceSize) {
                    // left side is a small set, so restart it in memory
                    crossLeftSingle(lhead, rhead :: rtail)
                  } else if (rempty && frontSize <= yggConfig.maxSliceSize) {
                    // right side is a small set, so restart it in memory
                    crossRightSingle(lhead :: ltail, rhead)
                  } else {
                    // both large sets, so just walk the left restarting the right.
                    crossBoth(this.slices, that.slices)
                  }
                }

              case None => M.point(StreamT.empty[M, Slice])
            }

          case None => M.point(StreamT.empty[M, Slice])
        }
      }
      
      // TODO: We should be able to fully compute the size of the result above.
      val newSize = (size, that.size) match {
        case (ExactSize(l), ExactSize(r))         => EstimateSize(l max r, l * r)
        case (EstimateSize(ln, lx), ExactSize(r)) => EstimateSize(ln max r, lx * r)
        case (ExactSize(l), EstimateSize(rn, rx)) => EstimateSize(l max rn, l * rx)
        case _ => UnknownSize // Bail on anything else for now (see above TODO)
      }
      
      val newSizeM = newSize match {
        case ExactSize(s) => Some(s)
        case EstimateSize(_, s) => Some(s)
        case _ => None
      }

      val sizeCheck = for (resultSize <- newSizeM) yield
        resultSize < yggConfig.maxSaneCrossSize && resultSize >= 0

      if (sizeCheck getOrElse true)
        Table(StreamT(cross0(composeSliceTransform2(spec)) map { tail => StreamT.Skip(tail) }), newSize)
      else
        throw EnormousCartesianException(this.size, that.size)
    }
    
    /**
     * Yields a new table with distinct rows. Assumes this table is sorted.
     */
    def distinct(spec: TransSpec1): Table = {
      def distinct0[T](id: SliceTransform1[Option[Slice]], filter: SliceTransform1[T]): Table = {
        def stream(state: (Option[Slice], T), slices: StreamT[M, Slice]): StreamT[M, Slice] = StreamT(
          for {
            head <- slices.uncons
          } yield
            head map { case (s, sx) =>
              val (prevFilter, cur) = id.f(state._1, s)
              val (nextT, curFilter) = filter.f(state._2, s)
              
              val next = cur.distinct(prevFilter, curFilter)
              
              StreamT.Yield(next, stream((if (next.size > 0) Some(curFilter) else prevFilter, nextT), sx))
            } getOrElse {
              StreamT.Done
            }
        )
        
        Table(stream((id.initial, filter.initial), slices), UnknownSize)
      }

      distinct0(SliceTransform.identity(None : Option[Slice]), composeSliceTransform(spec))
    }
    
    def takeRange(startIndex: Long, numberToTake: Long): Table = {
      def loop(stream: StreamT[M, Slice], readSoFar: Long): M[StreamT[M, Slice]] = stream.uncons flatMap {
        // Prior to first needed slice, so skip
        case Some((head, tail)) if (readSoFar + head.size) < (startIndex + 1) => loop(tail, readSoFar + head.size)
        // Somewhere in between, need to transition to splitting/reading
        case Some(_) if readSoFar < (startIndex + 1) => inner(stream, 0, (startIndex - readSoFar).toInt)
        // Read off the end (we took nothing)
        case _ => M.point(StreamT.empty[M, Slice])
      }
          
      def inner(stream: StreamT[M, Slice], takenSoFar: Long, sliceStartIndex: Int): M[StreamT[M, Slice]] = stream.uncons flatMap {
        case Some((head, tail)) if takenSoFar < numberToTake => {
          val needed = head.takeRange(sliceStartIndex, (numberToTake - takenSoFar).toInt)
          inner(tail, takenSoFar + (head.size - (sliceStartIndex)), 0).map(needed :: _)
        }
        case _ => M.point(StreamT.empty[M, Slice])
      }
      
      def calcNewSize(current: Long): Long = ((current-startIndex) max 0) min numberToTake

      val newSize = size match {
        case ExactSize(sz) => ExactSize(calcNewSize(sz))
        case EstimateSize(sMin, sMax) => EstimateSize(calcNewSize(sMin), calcNewSize(sMax))
        case UnknownSize => UnknownSize
      }

      Table(StreamT.wrapEffect(loop(slices, 0)), newSize)
    }

    /**
     * In order to call partitionMerge, the table must be sorted according to 
     * the values specified by the partitionBy transspec.
     */
    def partitionMerge(partitionBy: TransSpec1)(f: Table => M[Table]): M[Table] = {
      // Find the first element that compares LT
      @tailrec def findEnd(compare: Int => Ordering, imin: Int, imax: Int): Int = {
        val minOrd = compare(imin)
        if (minOrd eq EQ) {
          val maxOrd = compare(imax) 
          if (maxOrd eq EQ) {
            imax + 1
          } else if (maxOrd eq LT) {
            val imid = imin + ((imax - imin) / 2)
            val midOrd = compare(imid)
            if (midOrd eq LT) {
              findEnd(compare, imin, imid - 1)
            } else if (midOrd eq EQ) {
              findEnd(compare, imid, imax - 1)
            } else {
              sys.error("Inputs to partitionMerge not sorted.")
            }
          } else {
            sys.error("Inputs to partitionMerge not sorted.")
          }
        } else if ((minOrd eq LT) && (compare(imax) eq LT)) {
          imin
        } else {
          sys.error("Inputs to partitionMerge not sorted.")
        }
      }

      def subTable(comparatorGen: Slice => (Int => Ordering), slices: StreamT[M, Slice]): M[Table] = {
        def subTable0(slices: StreamT[M, Slice], subSlices: StreamT[M, Slice], size: Int): M[Table] = {
          slices.uncons flatMap {
            case Some((head, tail)) =>
              val headComparator = comparatorGen(head)
              val spanEnd = findEnd(headComparator, 0, head.size - 1)
              if (spanEnd < head.size) {
                M.point(Table(subSlices ++ (head.take(spanEnd) :: StreamT.empty[M, Slice]), ExactSize(size+spanEnd)))
              } else {
                subTable0(tail, subSlices ++ (head :: StreamT.empty[M, Slice]), size+head.size)
              }
              
            case None =>
              M.point(Table(subSlices, ExactSize(size)))
          }
        }
        
        subTable0(slices, StreamT.empty[M, Slice], 0)
      }

      def dropAndSplit(comparatorGen: Slice => (Int => Ordering), slices: StreamT[M, Slice], spanStart: Int): StreamT[M, Slice] = StreamT.wrapEffect {
        slices.uncons map {
          case Some((head, tail)) =>
            val headComparator = comparatorGen(head)
            val spanEnd = findEnd(headComparator, spanStart, head.size - 1)
            if (spanEnd < head.size) {
              stepPartition(head, spanEnd, tail)
            } else {
              dropAndSplit(comparatorGen, tail, 0)
            }
            
          case None =>
            StreamT.empty[M, Slice]
        }
      }

      def stepPartition(head: Slice, spanStart: Int, tail: StreamT[M, Slice]): StreamT[M, Slice] = {
        val comparatorGen = (s: Slice) => {
          val rowComparator = Slice.rowComparatorFor(head, s) { s0 =>
            s0.columns.keys collect {
              case ColumnRef(path @ CPath(CPathField("0"), _ @ _*), _) => path
            }
          }

          (i: Int) => rowComparator.compare(spanStart, i)
        }
        
        val groupTable = subTable(comparatorGen, head.drop(spanStart) :: tail)
        val groupedM = groupTable.map(_.transform(DerefObjectStatic(Leaf(Source), CPathField("1")))).flatMap(f)
        val groupedStream: StreamT[M, Slice] = StreamT.wrapEffect(groupedM.map(_.slices))

        groupedStream ++ dropAndSplit(comparatorGen, head :: tail, spanStart)
      }

      val keyTrans = OuterObjectConcat(
        WrapObject(partitionBy, "0"),
        WrapObject(Leaf(Source), "1")
      )

      this.transform(keyTrans).compact(TransSpec1.Id).slices.uncons map {
        case Some((head, tail)) =>
          Table(stepPartition(head, 0, tail), UnknownSize)
        case None =>
          Table.empty
      }
    }

    def normalize: Table = Table(slices.filter(!_.isEmpty), size)
    
    def renderJson(delimiter: Char = '\n'): StreamT[M, CharBuffer] = {
      val delimiterBuffer = {
        val back = CharBuffer.allocate(1)
        back.put(delimiter)
        back.flip()
        back
      }
      
      val delimitStream = delimiterBuffer :: StreamT.empty[M, CharBuffer]
      
      def foldFlatMap(slices: StreamT[M, Slice], rendered: Boolean): StreamT[M, CharBuffer] = {
        StreamT[M, CharBuffer](slices.step map {
          case StreamT.Yield(slice, tail) => {
            val (stream, rendered2) = slice.renderJson[M](delimiter)
            
            val stream2 = if (rendered && rendered2)
              delimitStream ++ stream
            else
              stream
            
            StreamT.Skip(stream2 ++ foldFlatMap(tail(), rendered || rendered2))
          }
          
          case StreamT.Skip(tail) => StreamT.Skip(foldFlatMap(tail(), rendered))
          
          case StreamT.Done => StreamT.Done
        })
      }
      
      foldFlatMap(slices, false)
    }

    def slicePrinter(prelude: String)(f: Slice => String): Table = {
      Table(StreamT(StreamT.Skip({println(prelude); slices map { s => println(f(s)); s }}).point[M]), size)
    }

    def logged(logger: Logger, logPrefix: String = "", prelude: String = "", appendix: String = "")(f: Slice => String): Table = {
      val preludeEffect = StreamT(StreamT.Skip({logger.debug(logPrefix + " " + prelude); StreamT.empty[M, Slice]}).point[M])
      val appendixEffect = StreamT(StreamT.Skip({logger.debug(logPrefix + " " + appendix); StreamT.empty[M, Slice]}).point[M])
      val sliceEffect = if (logger.isTraceEnabled) slices map { s => logger.trace(logPrefix + " " + f(s)); s } else slices
      Table(preludeEffect ++ sliceEffect ++ appendixEffect, size)
    }

    def printer(prelude: String = "", flag: String = ""): Table = slicePrinter(prelude)(s => s.toJsonString(flag))

    def toStrings: M[Iterable[String]] = {
      toEvents { (slice, row) => slice.toString(row) }
    }
    
    def toJson: M[Iterable[JValue]] = {
      toEvents { (slice, row) => slice.toJson(row) }
    }

    private def toEvents[A](f: (Slice, RowId) => Option[A]): M[Iterable[A]] = {
      for (stream <- self.compact(Leaf(Source)).slices.toStream) yield {
        for (slice <- stream; i <- 0 until slice.size; a <- f(slice, i)) yield a
      }
    }

    def metrics = TableMetrics(readStarts.get, blockReads.get)
  }
}
// vim: set ts=4 sw=4 et:
