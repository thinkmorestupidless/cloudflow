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

// Vendored from com.lightbend.akka:kube-actions 0.1.1 and updated for fabric8 6.x.
// Changes vs original:
//  - Removed Applicable interface (removed in fabric8 6.x); replaced with createOrReplace().
//  - Removed CascadingDeletable (removed in fabric8 6.x); foregroundDeletable now uses
//    a runtime cast to PropagationPolicyConfigurable.
//  - Removed crdContext from CustomResourceAdapter; uses client.resources(Class<T>) instead
//    of client.customResources(crdContext, ...) which was removed in fabric8 6.x.

package akka.kube.actions

import scala.reflect.ClassTag

import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.PropagationPolicyConfigurable
import io.fabric8.kubernetes.client.dsl.Deletable
import io.fabric8.kubernetes.client.dsl.Gettable
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource

sealed trait ResourceAdapter[T <: HasMetadata] {

  def gettable(client: KubernetesClient, resource: T): Gettable[T]

  def createOrReplace(client: KubernetesClient, resource: T): T

  // Returns a Deletable configured with FOREGROUND propagation policy.
  // fabric8 6.x: CascadingDeletable is removed; withPropagationPolicy is available at
  // runtime on BaseOperation (the actual Resource implementation) via a cast.
  def foregroundDeletable(client: KubernetesClient, resource: T): Deletable =
    resourceForDeletion(client, resource)
      .asInstanceOf[PropagationPolicyConfigurable[_ <: Deletable]]
      .withPropagationPolicy(DeletionPropagation.FOREGROUND)

  protected def resourceForDeletion(client: KubernetesClient, resource: T): AnyRef
}

final case class DefaultResourceAdapter[T <: HasMetadata]() extends ResourceAdapter[T] {

  def gettable(client: KubernetesClient, resource: T): Gettable[T] =
    client.resource[T](resource).fromServer()

  def createOrReplace(client: KubernetesClient, resource: T): T =
    client.resource[T](resource).createOrReplace()

  protected def resourceForDeletion(client: KubernetesClient, resource: T): AnyRef =
    client.resource[T](resource)
}

final case class CustomResourceAdapter[T <: CustomResource[_, _], L <: KubernetesResourceList[T]]()(implicit
    ct: ClassTag[T],
    cl: ClassTag[L])
    extends ResourceAdapter[T] {
  val elementClass: Class[T] = ct.runtimeClass.asInstanceOf[Class[T]]
  val listClass: Class[L] = cl.runtimeClass.asInstanceOf[Class[L]]

  // fabric8 6.x: client.resources(Class<T>) uses @Group/@Version/@Kind annotations on T.
  def customResources(client: KubernetesClient): MixedOperation[T, L, Resource[T]] =
    client.resources(elementClass).asInstanceOf[MixedOperation[T, L, Resource[T]]]

  def gettable(client: KubernetesClient, resource: T): Gettable[T] =
    customResources(client)
      .inNamespace(resource.getMetadata.getNamespace)
      .withName(resource.getMetadata.getName)

  def createOrReplace(client: KubernetesClient, resource: T): T =
    customResources(client)
      .inNamespace(resource.getMetadata.getNamespace)
      .withName(resource.getMetadata.getName)
      .createOrReplace()

  protected def resourceForDeletion(client: KubernetesClient, resource: T): AnyRef =
    customResources(client)
      .inNamespace(resource.getMetadata.getNamespace)
      .withName(resource.getMetadata.getName)

  def list(client: KubernetesClient, listOptions: ListOptions): L =
    customResources(client).inAnyNamespace
      .list(listOptions)
      .asInstanceOf[L]

  def list(client: KubernetesClient, namespace: String, listOptions: ListOptions): L =
    customResources(client)
      .inNamespace(namespace)
      .list(listOptions)
      .asInstanceOf[L]
}
