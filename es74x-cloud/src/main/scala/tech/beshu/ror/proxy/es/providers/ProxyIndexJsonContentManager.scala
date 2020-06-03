/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.providers

import java.util

import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.IndexJsonContentManager

// todo: implement
object ProxyIndexJsonContentManager extends IndexJsonContentManager {

  override def sourceOf(index: IndexName,
                        id: String): Task[Either[IndexJsonContentManager.ReadError, util.Map[String, _]]] =
    Task.now(Left(IndexJsonContentManager.CannotReachContentSource))

  override def saveContent(index: IndexName,
                           id: String,
                           content: util.Map[String, String]): Task[Either[IndexJsonContentManager.WriteError, Unit]] =
    Task.now(Right(()))
}
