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

import cats.implicits._
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

class CreateSnapshotEsRequestContext(actionRequest: CreateSnapshotRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[CreateSnapshotRequest](actionRequest, esContext, clusterService, threadPool) {

  override def snapshotsFrom(request: CreateSnapshotRequest): Set[SnapshotName] = Set {
    SnapshotName
      .from(request.snapshot())
      .getOrElse(throw RequestSeemsToBeInvalid[CreateSnapshotRequest]("Snapshot name is empty"))
  }

  override protected def repositoriesFrom(request: CreateSnapshotRequest): Set[RepositoryName] = Set {
    RepositoryName
      .from(request.repository())
      .getOrElse(throw RequestSeemsToBeInvalid[CreateSnapshotRequest]("Repository name is empty"))
  }

  override protected def indicesFrom(request: CreateSnapshotRequest): Set[IndexName] = {
    indicesOrWildcard(request.indices().asSafeSet.flatMap(IndexName.fromString))
  }

  override protected def modifyRequest(blockContext: SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshot <- snapshotFrom(blockContext)
      repository <- repositoryFrom(blockContext)
      indices <- indicesFrom(blockContext)
    } yield update(actionRequest, snapshot, repository, indices)
    updateResult match {
      case Right(_) =>
        ModificationResult.Modified
      case Left(_) =>
        logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.getSimpleName} request. It's safer to forbid the request, but it looks like an issue. Please, report it as soon as possible.")
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def snapshotFrom(blockContext: SnapshotRequestBlockContext) = {
    val snapshots = blockContext.snapshots.toList
    snapshots match {
      case Nil =>
        Left(())
      case snapshot :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${blockContext.requestContext.id.show}] Filtered result contains more than one snapshot. First was taken. The whole set of snapshots [${snapshots.mkString(",")}]")
        }
        Right(snapshot)
    }
  }

  private def repositoryFrom(blockContext: SnapshotRequestBlockContext) = {
    val repositories = blockContext.repositories.toList
    repositories match {
      case Nil =>
        Left(())
      case repository :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${blockContext.requestContext.id.show}] Filtered result contains more than one repository. First was taken. The whole set of repositories [${repositories.mkString(",")}]")
        }
        Right(repository)
    }
  }

  private def indicesFrom(blockContext: SnapshotRequestBlockContext) = {
    UniqueNonEmptyList.fromList(blockContext.filteredIndices.toList) match {
      case Some(value) => Right(value)
      case None => Left(())
    }
  }

  private def update(actionRequest: CreateSnapshotRequest,
                     snapshot: SnapshotName,
                     repository: RepositoryName,
                     indices: UniqueNonEmptyList[IndexName]) = {
    actionRequest.snapshot(SnapshotName.toString(snapshot))
    actionRequest.repository(RepositoryName.toString(repository))
    actionRequest.indices(indices.toList.map(_.stringify).asJava)
  }

}
