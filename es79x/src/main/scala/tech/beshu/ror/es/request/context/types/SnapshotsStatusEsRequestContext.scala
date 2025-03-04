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

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.admin.cluster.snapshots.status.SnapshotsStatusRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.accesscontrol.matchers.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

class SnapshotsStatusEsRequestContext(actionRequest: SnapshotsStatusRequest,
                                      esContext: EsContext,
                                      clusterService: RorClusterService,
                                      override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[SnapshotsStatusRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFrom(request: SnapshotsStatusRequest): Set[SnapshotName] =
    request
      .snapshots().asSafeList
      .flatMap(SnapshotName.from)
      .toSet[SnapshotName]

  override protected def repositoriesFrom(request: SnapshotsStatusRequest): Set[RepositoryName] = Set {
    RepositoryName
      .from(request.repository())
      .getOrElse(throw RequestSeemsToBeInvalid[SnapshotsStatusRequest]("Repository name is empty"))
  }

  override protected def indicesFrom(request: SnapshotsStatusRequest): Set[IndexName] =
    Set(IndexName.Local.wildcard)

  override protected def modifyRequest(blockContext: SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshots <- snapshotsFrom(blockContext)
      repository <- repositoryFrom(blockContext)
    } yield update(actionRequest, snapshots, repository)
    updateResult match {
      case Right(_) =>
        ModificationResult.Modified
      case Left(_) =>
        logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.getSimpleName} request. It's safer to forbid the request, but it looks like an issue. Please, report it as soon as possible.")
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def snapshotsFrom(blockContext: SnapshotRequestBlockContext) = {
    NonEmptyList.fromList(fullNamedSnapshotsFrom(blockContext.snapshots).toList) match {
      case Some(list) => Right(list)
      case None => Left(())
    }
  }

  private def fullNamedSnapshotsFrom(snapshots: Iterable[SnapshotName]): Set[SnapshotName.Full] = {
    val allFullNameSnapshots: Set[SnapshotName.Full] = allSnapshots.values.toSet.flatten
    MatcherWithWildcardsScalaAdapter
      .create(snapshots)
      .filter(allFullNameSnapshots)
  }

  private def repositoryFrom(blockContext: SnapshotRequestBlockContext) = {
    val repositories = fullNamedRepositoriesFrom(blockContext.repositories).toList
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

  private def fullNamedRepositoriesFrom(repositories: Iterable[RepositoryName]): Set[RepositoryName.Full] = {
    val allFullNameRepositories: Set[RepositoryName.Full] = allSnapshots.keys.toSet
    MatcherWithWildcardsScalaAdapter
      .create(repositories)
      .filter(allFullNameRepositories)
  }

  private def update(actionRequest: SnapshotsStatusRequest,
                     snapshots: NonEmptyList[SnapshotName.Full],
                     repository: RepositoryName.Full) = {
    actionRequest.snapshots(snapshots.toList.map(SnapshotName.toString).toArray)
    actionRequest.repository(RepositoryName.toString(repository))
  }
}
