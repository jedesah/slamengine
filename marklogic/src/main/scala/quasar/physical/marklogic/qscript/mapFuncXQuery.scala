/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.physical.marklogic.qscript

import quasar.Predef._
import quasar.physical.marklogic.xquery.XQuery
import quasar.qscript.{MapFunc, MapFuncs}, MapFuncs._

import matryoshka.Algebra

object mapFuncXQuery {
  def apply[T[_[_]]]: Algebra[MapFunc[T, ?], XQuery] = {
    case v @ ToString(a1) => ???
    case v => s" ???(MapFunc - $v)??? "
  }
}
