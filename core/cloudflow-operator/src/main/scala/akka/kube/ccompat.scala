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

// Vendored from com.lightbend.akka:kube-actions 0.1.1 (no changes needed).

package akka.kube

import java.{ util => jul }

object ccompat {

  def asScala[T](l: jul.List[T]): Vector[T] = {
    import scala.jdk.CollectionConverters._
    l.asScala.toVector
  }

  def asJava[T](l: List[T]): jul.List[T] = {
    import scala.jdk.CollectionConverters._
    l.asJava
  }
}
