/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.marklogic

import slamdata.Predef._
import quasar.contrib.scalaz.MonadError_
import quasar.ejson.{EJson, Str}
import quasar.fp.coproductShow
import quasar.fp.ski.κ
import quasar.contrib.pathy.{AFile, UriPathCodec}
import quasar.contrib.scalaz.MonadError_
import quasar.physical.marklogic.xquery._
import quasar.physical.marklogic.xquery.syntax._
import quasar.qscript._

import matryoshka.{Hole => _, _}
import matryoshka.data._
import matryoshka.implicits._
import matryoshka.patterns._
import scalaz._, Scalaz._

package object qscript {
  type MarkLogicPlanErrT[F[_], A] = EitherT[F, MarkLogicPlannerError, A]

  type MonadPlanErr[F[_]] = MonadError_[F, MarkLogicPlannerError]

  object MonadPlanErr {
    def apply[F[_]](implicit F: MonadPlanErr[F]): MonadPlanErr[F] = F
  }

  /** Matches "iterative" FLWOR expressions, those involving at least one `for` clause. */
  object IterativeFlwor {
    def unapply(xqy: XQuery): Option[(NonEmptyList[BindingClause], Option[XQuery], IList[(XQuery, SortDirection)], Boolean, XQuery)] = xqy match {
      case XQuery.Flwor(clauses, filter, order, isStable, result) if clauses.any(BindingClause.forClause.nonEmpty) =>
        Some((clauses, filter, order, isStable, result))

      case _ => None
    }
  }

  val EJsonTypeKey  = "_ejson.type"
  val EJsonValueKey = "_ejson.value"

  /** XQuery evaluating to the documents having the specified format in the directory. */
  def directoryDocuments[FMT: SearchOptions](uri: XQuery, includeDescendants: Boolean): XQuery =
    cts.search(
      expr    = fn.doc(),
      query   = cts.directoryQuery(uri, (includeDescendants ? "infinity" | "1").xs),
      options = SearchOptions[FMT].searchOptions)

  /** XQuery evaluating to the document node at the given URI. */
  def documentNode[FMT: SearchOptions](uri: XQuery): XQuery =
    cts.search(
      expr    = fn.doc(),
      query   = cts.documentQuery(uri),
      options = SearchOptions[FMT].searchOptions)

  /** XQuery evaluating to the document node at the given path. */
  def fileNode[FMT: SearchOptions](file: AFile): XQuery =
    documentNode[FMT](UriPathCodec.printPath(file).xs)

  /** XQuery evaluating to the root node of the document at the given path. */
  def fileRoot[FMT: SearchOptions](file: AFile): XQuery =
    fileNode[FMT](file) `/` axes.child.node()

  def mapFuncXQuery[T[_[_]]: BirecursiveT, F[_]: Monad: MonadPlanErr, FMT](
    fm: FreeMap[T],
    src: XQuery
  )(implicit
    MFP: Planner[F, FMT, MapFuncCore[T, ?]],
    SP:  StructuralPlanner[F, FMT]
  ): F[XQuery] =
    fm.project match {
      case MapFuncCore.StaticArray(elements) =>
        for {
          xqyElts <- elements.traverse(planMapFunc[T, F, FMT, Hole](_)(κ(src)))
          arrElts <- xqyElts.traverse(SP.mkArrayElt)
          arr     <- SP.mkArray(mkSeq(arrElts))
        } yield arr

      case MapFuncCore.StaticMap(entries) =>
        for {
          xqyKV <- entries.traverse(_.bitraverse({
                     case Embed(MapFuncCore.EC(Str(s))) => s.xs.point[F]
                     case key                       => invalidQName[F, XQuery](key.convertTo[Fix[EJson]].shows)
                   },
                   planMapFunc[T, F, FMT, Hole](_)(κ(src))))
          elts  <- xqyKV.traverse((SP.mkObjectEntry _).tupled)
          map   <- SP.mkObject(mkSeq(elts))
        } yield map

      case other => planMapFunc[T, F, FMT, Hole](other.embed)(κ(src))
    }

  def mergeXQuery[T[_[_]]: BirecursiveT, F[_]: Monad, FMT](
    jf: JoinFunc[T],
    l: XQuery,
    r: XQuery
  )(implicit
    MFP: Planner[F, FMT, MapFuncCore[T, ?]]
  ): F[XQuery] =
    planMapFunc[T, F, FMT, JoinSide](jf) {
      case LeftSide  => l
      case RightSide => r
    }

  def planMapFunc[T[_[_]]: BirecursiveT, F[_]: Monad, FMT, A](
    freeMap: FreeMapA[T, A])(
    recover: A => XQuery
  )(implicit
    MFP: Planner[F, FMT, MapFuncCore[T, ?]]
  ): F[XQuery] =
    freeMap.transCata[FreeMapA[T, A]](rewriteNullCheck[T, FreeMapA[T, A], A]).cataM(interpretM(recover(_).point[F], MFP.plan))

  def rebaseXQuery[T[_[_]], F[_]: Monad, FMT](
    fqs: FreeQS[T],
    src: XQuery
  )(implicit
    QTP: Planner[F, FMT, QScriptTotal[T, ?]]
  ): F[XQuery] =
    fqs.cataM(interpretM(κ(src.point[F]), QTP.plan))

  def rewriteNullCheck[T[_[_]]: BirecursiveT, U, E](
    implicit UR: Recursive.Aux[U, CoEnv[E, MapFuncCore[T, ?], ?]],
             UC: Corecursive.Aux[U, CoEnv[E, MapFuncCore[T, ?], ?]]
  ): CoEnv[E, MapFuncCore[T, ?], U] => CoEnv[E, MapFuncCore[T, ?], U] = {

    import quasar.qscript.MapFuncsCore.{Eq, Neq, TypeOf, Constant}
    import quasar.ejson._
    import matryoshka._

    object NullLit {
      def unapply[T[_[_]]: RecursiveT, A](mfc: CoEnv[E, MapFuncCore[T, ?], A]): Boolean =
        mfc.run.exists[MapFuncCore[T, A]] {
          case Constant(ej) => EJson.isNull(ej)
          case _            => false
        }
    }

    def stringLit(str: String): Constant[T, U] =
      Constant[T, U](EJson.fromCommon(Str[T[EJson]](str)))

    val nullString: U = UC.embed(CoEnv(stringLit("null").right))

    fa => CoEnv(fa.run.map {
      case Eq(lhs, Embed(NullLit()))  => Eq(UC.embed (CoEnv(TypeOf(lhs).right)), nullString)
      case Eq(Embed(NullLit()), rhs)  => Eq(UC.embed (CoEnv(TypeOf(rhs).right)), nullString)
      case Neq(lhs, Embed(NullLit())) => Neq(UC.embed(CoEnv(TypeOf(lhs).right)), nullString)
      case Neq(Embed(NullLit()), rhs) => Neq(UC.embed(CoEnv(TypeOf(rhs).right)), nullString)
      case other             => other
    })
  }

  ////

  private def invalidQName[F[_]: MonadPlanErr, A](s: String): F[A] =
    MonadError_[F, MarkLogicPlannerError].raiseError(
      MarkLogicPlannerError.invalidQName(s))
}
