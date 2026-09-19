/*
 * Copyright (C) 2016-2026 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.pekkostream.scaladsl

import org.apache.pekko.stream.scaladsl.RunnableGraph

import cloudflow.pekkostream._

/** Can be used to define a [[cloudflow.pekkostream.PekkoStreamletLogic]] from a `RunnableGraph[_]`, which will be
  * materialized and instrumented when the [[cloudflow.pekkostream.PekkoServerStreamlet]] is run.
  */
abstract class RunnableGraphStreamletLogic(implicit context: PekkoStreamletContext) extends PekkoStreamletLogic {

  /** This method needs to return a `RunnableGraph` that is connected to inlet(s) and/or outlet(s) of the streamlet. See
    * [[cloudflow.pekkostream.PekkoStreamletLogic]] for more information how to create
    * `org.apache.pekko.stream.javadsl.Source`s and `org.apache.pekko.stream.javadsl.Sink`s to inlets and outlets
    * respectively.
    */
  def runnableGraph: RunnableGraph[_]

  override def run(): Unit = runGraph(runnableGraph)
}
