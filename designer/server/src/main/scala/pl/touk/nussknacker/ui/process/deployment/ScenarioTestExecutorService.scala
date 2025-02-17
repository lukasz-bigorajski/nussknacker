package pl.touk.nussknacker.ui.process.deployment

import pl.touk.nussknacker.engine.api.process.ProcessIdWithName
import pl.touk.nussknacker.engine.api.test.ScenarioTestData
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.testmode.TestProcess.TestResults
import pl.touk.nussknacker.engine.api.process.ProcessingType
import pl.touk.nussknacker.ui.security.api.LoggedUser

import scala.concurrent.{ExecutionContext, Future}

trait ScenarioTestExecutorService {

  def testProcess(
      id: ProcessIdWithName,
      canonicalProcess: CanonicalProcess,
      processingType: ProcessingType,
      scenarioTestData: ScenarioTestData,
  )(implicit loggedUser: LoggedUser, ec: ExecutionContext): Future[TestResults]

}

class ScenarioTestExecutorServiceImpl(scenarioResolver: ScenarioResolver, dispatcher: DeploymentManagerDispatcher)
    extends ScenarioTestExecutorService {

  override def testProcess(
      id: ProcessIdWithName,
      canonicalProcess: CanonicalProcess,
      processingType: ProcessingType,
      scenarioTestData: ScenarioTestData
  )(implicit loggedUser: LoggedUser, ec: ExecutionContext): Future[TestResults] = {
    for {
      resolvedProcess <- Future.fromTry(scenarioResolver.resolveScenario(canonicalProcess, processingType))
      manager = dispatcher.deploymentManagerUnsafe(processingType)
      testResult <- manager.test(id.name, resolvedProcess, scenarioTestData)
    } yield testResult
  }

}
