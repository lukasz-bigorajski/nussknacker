package pl.touk.nussknacker.ui.process.repository

import pl.touk.nussknacker.engine.api.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.engine.api.process.{ProcessId, ProcessName, VersionId}
import pl.touk.nussknacker.ui.api.ListenerApiUser
import pl.touk.nussknacker.ui.listener.{ListenerScenarioWithDetails, User}
import pl.touk.nussknacker.ui.listener.services.{PullProcessRepository => ListenerPullProcessRepository}
import pl.touk.nussknacker.ui.security.api.LoggedUser

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

class PullProcessRepository(fetchingProcessRepository: FetchingProcessRepository[Future])
    extends ListenerPullProcessRepository {

  private implicit def toLoggedUser(implicit user: User): LoggedUser =
    user.asInstanceOf[ListenerApiUser].loggedUser

  override def fetchLatestProcessDetailsForProcessId(
      id: ProcessId
  )(implicit listenerUser: User, ec: ExecutionContext): Future[Option[ListenerScenarioWithDetails]] = {
    fetchingProcessRepository.fetchLatestProcessDetailsForProcessId[DisplayableProcess](id = id)
  }

  override def fetchProcessDetailsForId(
      processId: ProcessId,
      versionId: VersionId
  )(implicit listenerUser: User, ec: ExecutionContext): Future[Option[ListenerScenarioWithDetails]] = {
    fetchingProcessRepository.fetchProcessDetailsForId[DisplayableProcess](processId, versionId)
  }

  override def fetchProcessDetailsForName(
      processName: ProcessName,
      versionId: VersionId
  )(implicit listenerUser: User, ec: ExecutionContext): Future[Option[ListenerScenarioWithDetails]] = for {
    maybeProcessId <- fetchingProcessRepository.fetchProcessId(processName)
    processId <- maybeProcessId.fold(
      Future.failed[ProcessId](new IllegalArgumentException(s"ProcessId for $processName not found"))
    )(Future.successful)
    processDetails <- fetchProcessDetailsForId(processId, versionId)
  } yield processDetails

}
