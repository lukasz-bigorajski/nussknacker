package pl.touk.nussknacker.ui.api.helpers

import cats.instances.future._
import pl.touk.nussknacker.engine.api.deployment.ProcessActionType
import pl.touk.nussknacker.engine.api.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.engine.api.process.{ProcessId, ProcessName, VersionId}
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.ui.process.repository.ScenarioShapeFetchStrategy.{
  FetchCanonical,
  FetchComponentsUsages,
  FetchDisplayable,
  NotFetch
}
import pl.touk.nussknacker.security.Permission
import pl.touk.nussknacker.ui.db.DbRef
import pl.touk.nussknacker.ui.db.entity.ProcessEntityData
import pl.touk.nussknacker.ui.process.ScenarioQuery
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter
import pl.touk.nussknacker.ui.process.repository.{
  BasicRepository,
  FetchingProcessRepository,
  ScenarioComponentsUsagesHelper,
  ScenarioShapeFetchStrategy,
  ScenarioWithDetailsEntity
}
import pl.touk.nussknacker.ui.security.api.LoggedUser

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds

object MockFetchingProcessRepository {

  def withProcessesDetails(
      processes: List[ScenarioWithDetailsEntity[DisplayableProcess]]
  )(implicit ec: ExecutionContext): MockFetchingProcessRepository = {
    val canonicals = processes.map { p => p.mapScenario(ProcessConverter.fromDisplayable) }

    new MockFetchingProcessRepository(
      TestFactory.dummyDbRef, // It's only for BasicRepository implementation, we don't use it
      canonicals
    )
  }

}

class MockFetchingProcessRepository private (
    override val dbRef: DbRef,
    processes: List[ScenarioWithDetailsEntity[CanonicalProcess]]
)(implicit ec: ExecutionContext)
    extends FetchingProcessRepository[Future]
    with BasicRepository {

  override def fetchLatestProcessesDetails[PS: ScenarioShapeFetchStrategy](
      q: ScenarioQuery
  )(implicit loggedUser: LoggedUser, ec: ExecutionContext): Future[List[ScenarioWithDetailsEntity[PS]]] =
    getUserProcesses[PS].map(
      _.filter(p =>
        check(q.isFragment, p.isFragment) && check(q.isArchived, p.isArchived) && check(
          q.isDeployed,
          p.lastStateAction.exists(_.actionType.equals(ProcessActionType.Deploy))
        ) && checkSeq(q.categories, p.processCategory) && checkSeq(q.processingTypes, p.processingType)
      )
    )

  override def fetchLatestProcessDetailsForProcessId[PS: ScenarioShapeFetchStrategy](
      id: ProcessId
  )(implicit loggedUser: LoggedUser, ec: ExecutionContext): Future[Option[ScenarioWithDetailsEntity[PS]]] =
    getUserProcesses[PS].map(_.filter(p => p.processId == id).lastOption)

  override def fetchProcessDetailsForId[PS: ScenarioShapeFetchStrategy](processId: ProcessId, versionId: VersionId)(
      implicit loggedUser: LoggedUser,
      ec: ExecutionContext
  ): Future[Option[ScenarioWithDetailsEntity[PS]]] =
    getUserProcesses[PS].map(_.find(p => p.processId == processId && p.processVersionId == versionId))

  override def fetchProcessId(processName: ProcessName)(implicit ec: ExecutionContext): Future[Option[ProcessId]] =
    Future(processes.find(p => p.name == processName).map(_.processId))

  override def fetchProcessName(processId: ProcessId)(implicit ec: ExecutionContext): Future[Option[ProcessName]] =
    Future(processes.find(p => p.processId == processId).map(_.name))

  override def fetchProcessingType(
      processId: ProcessId
  )(implicit loggedUser: LoggedUser, ec: ExecutionContext): Future[String] =
    getUserProcesses[Unit].map(_.find(p => p.processId == processId).map(_.processingType).get)

  private def getUserProcesses[PS: ScenarioShapeFetchStrategy](implicit loggedUser: LoggedUser) =
    getProcesses[PS].map(_.filter(p => loggedUser.isAdmin || loggedUser.can(p.processCategory, Permission.Read)))

  private def getProcesses[PS: ScenarioShapeFetchStrategy]: Future[List[ScenarioWithDetailsEntity[PS]]] = {
    val shapeStrategy: ScenarioShapeFetchStrategy[PS] = implicitly[ScenarioShapeFetchStrategy[PS]]
    Future(processes.map(p => convertProcess(p)(shapeStrategy)))
  }

  private def convertProcess[PS: ScenarioShapeFetchStrategy](
      process: ScenarioWithDetailsEntity[CanonicalProcess]
  ): ScenarioWithDetailsEntity[PS] = {
    val shapeStrategy: ScenarioShapeFetchStrategy[PS] = implicitly[ScenarioShapeFetchStrategy[PS]]

    shapeStrategy match {
      case NotFetch       => process.copy(json = ().asInstanceOf[PS])
      case FetchCanonical => process.asInstanceOf[ScenarioWithDetailsEntity[PS]]
      case FetchDisplayable =>
        process
          .mapScenario(canonical =>
            ProcessConverter.toDisplayableOrDie(canonical, process.processingType, process.processCategory)
          )
          .asInstanceOf[ScenarioWithDetailsEntity[PS]]
      case FetchComponentsUsages =>
        process
          .mapScenario(canonical => ScenarioComponentsUsagesHelper.compute(canonical))
          .asInstanceOf[ScenarioWithDetailsEntity[PS]]
    }
  }

  private def check[T](condition: Option[T], value: T) = condition.forall(_ == value)

  private def checkSeq[T](condition: Option[Seq[T]], value: T) = condition.forall(_.contains(value))
}
