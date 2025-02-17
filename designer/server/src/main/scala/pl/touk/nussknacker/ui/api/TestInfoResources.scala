package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server._
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import pl.touk.nussknacker.engine.api.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.engine.definition.test.TestingCapabilities
import pl.touk.nussknacker.ui.process.ProcessService
import pl.touk.nussknacker.ui.process.repository.FetchingProcessRepository
import pl.touk.nussknacker.ui.process.test.ScenarioTestService
import pl.touk.nussknacker.ui.security.api.LoggedUser

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class TestInfoResources(
    val processAuthorizer: AuthorizeProcess,
    protected val processService: ProcessService,
    scenarioTestService: ScenarioTestService
)(implicit val ec: ExecutionContext)
    extends Directives
    with FailFastCirceSupport
    with RouteWithUser
    with AuthorizeProcessDirectives
    with ProcessDirectives {

  implicit val timeout: Timeout = Timeout(1 minute)

  def securedRoute(implicit user: LoggedUser): Route = {
    // TODO: is Write enough here?
    pathPrefix("testInfo") {
      post {
        entity(as[DisplayableProcess]) { displayableProcess =>
          processId(displayableProcess.name) { idWithName =>
            canDeploy(idWithName.id) {
              path("capabilities") {
                complete {
                  scenarioTestService.getTestingCapabilities(displayableProcess)
                }
              } ~ path("testParameters") {
                complete {
                  scenarioTestService.testParametersDefinition(displayableProcess)
                }
              } ~ path("generate" / IntNumber) { testSampleSize =>
                complete {
                  scenarioTestService.generateData(displayableProcess, testSampleSize) match {
                    case Left(error)                => HttpResponse(StatusCodes.BadRequest, entity = error)
                    case Right(rawScenarioTestData) => HttpResponse(entity = rawScenarioTestData.content)
                  }
                }
              }
            } ~ path("capabilities") {
              complete(TestingCapabilities.Disabled)
            }
          }
        }
      }
    }
  }

}
