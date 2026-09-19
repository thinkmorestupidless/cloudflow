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

package cloudflow.operator.action

import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._

import org.apache.kafka.clients.admin.{ Admin, OffsetSpec }
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition

import TopicActions.KafkaFutureConverter

/** Moves a consumer group's committed offsets on one topic back to the earliest offset of each partition. */
object ConsumerGroupReset {

  final case class GroupHasActiveMembers(groupId: String, members: Int)
      extends RuntimeException(
        s"consumer group [$groupId] has $members active member(s); scale its streamlet to 0 and wait for its pods to " +
          "stop before resetting it")

  /** @return
    *   the number of partitions reset.
    */
  def toEarliest(admin: Admin, groupId: String, topic: String)(implicit ec: ExecutionContext): Future[Int] =
    for {
      group <- admin.describeConsumerGroups(List(groupId).asJava).all().asScala.map(_.get(groupId))
      // Kafka also refuses to alter the offsets of a group with members, but its error names neither the group nor
      // what to do about it.
      _ <-
        if (group.members.isEmpty) Future.unit
        else Future.failed(GroupHasActiveMembers(groupId, group.members.size))
      description <- admin.describeTopics(List(topic).asJava).allTopicNames().asScala.map(_.get(topic))
      partitions = description.partitions.asScala.map(p => new TopicPartition(topic, p.partition)).toList
      earliest <- admin.listOffsets(partitions.map(_ -> OffsetSpec.earliest()).toMap.asJava).all().asScala
      offsets = earliest.asScala.map { case (partition, info) => partition -> new OffsetAndMetadata(info.offset) }
      _ <- admin.alterConsumerGroupOffsets(groupId, offsets.asJava).all().asScala
    } yield partitions.size
}
