/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{IndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}

abstract class BaseSnapshotEsRequestContext[T <: ActionRequest](actionRequest: T,
                                                                esContext: EsContext,
                                                                clusterService: RorClusterService,
                                                                override val threadPool: ThreadPool)
  extends BaseEsRequestContext[SnapshotRequestBlockContext](esContext, clusterService)
    with EsRequest[SnapshotRequestBlockContext] {

  override val initialBlockContext: SnapshotRequestBlockContext = SnapshotRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    List.empty,
    snapshotsOrWildcard(snapshotsFrom(actionRequest)),
    repositoriesOrWildcard(repositoriesFrom(actionRequest)),
    indicesFrom(actionRequest),
    Set(IndexName.Local.wildcard)
  )

  protected def snapshotsFrom(request: T): Set[SnapshotName]

  protected def repositoriesFrom(request: T): Set[RepositoryName]

  protected def indicesFrom(request: T): Set[IndexName]
}
