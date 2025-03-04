package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpResponse, MessageEntity, StatusCode, StatusCodes}
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import cats.data.Validated.{Invalid, Valid}
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json, parser}
import io.dropwizard.metrics5.MetricRegistry
import pl.touk.nussknacker.engine.ModelData
import pl.touk.nussknacker.engine.api.component.{ComponentInfo, NodeComponentInfo}
import pl.touk.nussknacker.engine.api.deployment._
import pl.touk.nussknacker.engine.api.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.engine.api.exception.NuExceptionInfo
import pl.touk.nussknacker.engine.api.{Context, DisplayJson}
import pl.touk.nussknacker.engine.testmode.TestProcess._
import pl.touk.nussknacker.engine.util.json.BestEffortJsonEncoder
import pl.touk.nussknacker.restmodel.{CustomActionRequest, CustomActionResponse}
import pl.touk.nussknacker.ui.BadRequestError
import pl.touk.nussknacker.ui.api.NodesResources.prepareTestFromParametersDecoder
import pl.touk.nussknacker.ui.api.ProcessesResources.ProcessUnmarshallingError
import pl.touk.nussknacker.ui.metrics.TimeMeasuring.measureTime
import pl.touk.nussknacker.ui.process.ProcessService
import pl.touk.nussknacker.ui.process.deployment.LoggedUserConversions.LoggedUserOps
import pl.touk.nussknacker.ui.process.deployment.{
  CustomActionInvokerService,
  DeploymentManagerDispatcher,
  DeploymentService
}
import pl.touk.nussknacker.ui.process.processingtypedata.ProcessingTypeDataProvider
import pl.touk.nussknacker.ui.process.repository.DeploymentComment
import pl.touk.nussknacker.ui.process.test.{RawScenarioTestData, ResultsWithCounts, ScenarioTestService}
import pl.touk.nussknacker.ui.security.api.LoggedUser

import scala.concurrent.{ExecutionContext, Future}

object ManagementResources {

  import pl.touk.nussknacker.engine.api.CirceUtil._

  implicit val resultsWithCountsEncoder: Encoder[ResultsWithCounts] = deriveConfiguredEncoder

  implicit val testResultsEncoder: Encoder[TestResults] = new Encoder[TestResults]() {

    implicit val anyEncoder: Encoder[Any] = {
      case displayable: DisplayJson =>
        def safeString(a: String) = Option(a).map(Json.fromString).getOrElse(Json.Null)

        val displayableJson = displayable.asJson
        displayable.originalDisplay match {
          case None           => Json.obj("pretty" -> displayableJson)
          case Some(original) => Json.obj("original" -> safeString(original), "pretty" -> displayableJson)
        }
      case null => Json.Null
      case a =>
        Json.obj(
          "pretty" -> BestEffortJsonEncoder(failOnUnknown = false, a.getClass.getClassLoader).circeEncoder.apply(a)
        )
    }

    // TODO: do we want more information here?
    implicit val contextEncoder: Encoder[Context] = (a: Context) =>
      Json.obj(
        "id"        -> Json.fromString(a.id),
        "variables" -> a.variables.asJson
      )

    implicit val componentInfoEncoder: Encoder[ComponentInfo]         = deriveConfiguredEncoder
    implicit val nodeComponentInfoEncoder: Encoder[NodeComponentInfo] = deriveConfiguredEncoder

    val throwableEncoder: Encoder[Throwable] = Encoder[Option[String]].contramap(th => Option(th.getMessage))

    // It has to be done manually, deriveConfiguredEncoder doesn't work properly with value: Any
    implicit val externalInvocationResultEncoder: Encoder[ExternalInvocationResult] =
      (value: ExternalInvocationResult) =>
        Json.obj(
          "name"      -> Json.fromString(value.name),
          "contextId" -> Json.fromString(value.contextId),
          "value"     -> value.value.asJson,
        )

    // It has to be done manually, deriveConfiguredEncoder doesn't work properly with value: Any
    implicit val expressionInvocationResultEncoder: Encoder[ExpressionInvocationResult] =
      (value: ExpressionInvocationResult) =>
        Json.obj(
          "name"      -> Json.fromString(value.name),
          "contextId" -> Json.fromString(value.contextId),
          "value"     -> value.value.asJson,
        )

    implicit val exceptionsEncoder: Encoder[NuExceptionInfo[_ <: Throwable]] =
      (value: NuExceptionInfo[_ <: Throwable]) =>
        Json.obj(
          "nodeComponentInfo" -> value.nodeComponentInfo.asJson,
          "throwable"         -> throwableEncoder(value.throwable),
          "context"           -> value.context.asJson
        )

    override def apply(a: TestResults): Json = a match {
      case TestResults(nodeResults, invocationResults, externalInvocationResults, exceptions) =>
        Json.obj(
          "nodeResults"       -> nodeResults.map { case (node, list) => node -> list.sortBy(_.id) }.asJson,
          "invocationResults" -> invocationResults.map { case (node, list) => node -> list.sortBy(_.contextId) }.asJson,
          "externalInvocationResults" -> externalInvocationResults.map { case (node, list) =>
            node -> list.sortBy(_.contextId)
          }.asJson,
          "exceptions" -> exceptions.sortBy(_.context.id).asJson
        )
    }

  }

}

class ManagementResources(
    val processAuthorizer: AuthorizeProcess,
    protected val processService: ProcessService,
    deploymentCommentSettings: Option[DeploymentCommentSettings],
    deploymentService: DeploymentService,
    dispatcher: DeploymentManagerDispatcher,
    customActionInvokerService: CustomActionInvokerService,
    metricRegistry: MetricRegistry,
    scenarioTestService: ScenarioTestService,
    typeToConfig: ProcessingTypeDataProvider[ModelData, _]
)(implicit val ec: ExecutionContext)
    extends Directives
    with LazyLogging
    with RouteWithUser
    with FailFastCirceSupport
    with AuthorizeProcessDirectives
    with ProcessDirectives {

  import ManagementResources._

  // TODO: in the future we could use https://github.com/akka/akka-http/pull/1828 when we can bump version to 10.1.x
  private implicit final val plainBytes: FromEntityUnmarshaller[Array[Byte]] = Unmarshaller.byteArrayUnmarshaller
  private implicit final val plainString: FromEntityUnmarshaller[String]     = Unmarshaller.stringUnmarshaller

  sealed case class ValidationError(message: String) extends BadRequestError(message)

  private def withDeploymentComment: Directive1[Option[DeploymentComment]] = {
    entity(as[Option[String]]).flatMap { comment =>
      DeploymentComment.createDeploymentComment(comment, deploymentCommentSettings) match {
        case Valid(deploymentComment) => provide(deploymentComment)
        case Invalid(exc) => complete(NuDesignerErrorToHttp.httpResponseFrom(ValidationError(exc.getMessage)))
      }
    }
  }

  def securedRoute(implicit user: LoggedUser): Route = {
    pathPrefix("adminProcessManagement") {
      path("snapshot" / ProcessNameSegment) { processName =>
        (post & processId(processName) & parameters(Symbol("savepointDir").?)) { (processId, savepointDir) =>
          canDeploy(processId) {
            complete {
              convertSavepointResultToResponse(
                dispatcher
                  .deploymentManagerUnsafe(processId.id)(ec, user)
                  .flatMap(_.savepoint(processId.name, savepointDir))
              )
            }
          }
        }
      } ~
        path("stop" / ProcessNameSegment) { processName =>
          (post & processId(processName) & parameters(Symbol("savepointDir").?)) { (processId, savepointDir) =>
            canDeploy(processId) {
              complete {
                convertSavepointResultToResponse(
                  dispatcher
                    .deploymentManagerUnsafe(processId.id)(ec, user)
                    .flatMap(_.stop(processId.name, savepointDir, user.toManagerUser))
                )
              }
            }
          }
        } ~
        path("deploy" / ProcessNameSegment) { processName =>
          (post & processId(processName) & parameters(Symbol("savepointPath"))) { (processId, savepointPath) =>
            canDeploy(processId) {
              withDeploymentComment { deploymentComment =>
                complete {
                  deploymentService
                    .deployProcessAsync(processId, Some(savepointPath), deploymentComment)
                    .map(_ => ())
                }
              }
            }
          }
        }
    } ~
      pathPrefix("processManagement") {
        path("deploy" / ProcessNameSegment) { processName =>
          (post & processId(processName)) { processId =>
            canDeploy(processId) {
              withDeploymentComment { deploymentComment =>
                complete {
                  measureTime("deployment", metricRegistry) {
                    deploymentService
                      .deployProcessAsync(processId, None, deploymentComment)
                      .map(_ => ())
                  }
                }
              }
            }
          }
        } ~
          path("cancel" / ProcessNameSegment) { processName =>
            (post & processId(processName)) { processId =>
              canDeploy(processId) {
                withDeploymentComment { deploymentComment =>
                  complete {
                    measureTime("cancel", metricRegistry) {
                      deploymentService.cancelProcess(processId, deploymentComment)
                    }
                  }
                }
              }
            }
          } ~
          // TODO: maybe Write permission is enough here?
          path("test" / ProcessNameSegment) { processName =>
            (post & processId(processName)) { idWithName =>
              canDeploy(idWithName.id) {
                formFields(Symbol("testData"), Symbol("processJson")) { (testDataContent, displayableProcessJson) =>
                  complete {
                    measureTime("test", metricRegistry) {
                      parser.parse(displayableProcessJson).flatMap(Decoder[DisplayableProcess].decodeJson) match {
                        case Right(displayableProcess) =>
                          scenarioTestService
                            .performTest(
                              idWithName,
                              displayableProcess,
                              RawScenarioTestData(testDataContent)
                            )
                            .flatMap { results =>
                              Marshal(results).to[MessageEntity].map(en => HttpResponse(entity = en))
                            }
                        case Left(error) =>
                          Future.failed(ProcessUnmarshallingError(error.toString))
                      }
                    }
                  }
                }
              }
            }
          } ~
          path("generateAndTest" / IntNumber) { testSampleSize =>
            {
              (post & entity(as[DisplayableProcess])) { displayableProcess =>
                {
                  processId(displayableProcess.name) { idWithName =>
                    canDeploy(idWithName) {
                      complete {
                        measureTime("generateAndTest", metricRegistry) {
                          scenarioTestService.generateData(displayableProcess, testSampleSize) match {
                            case Left(error) => Future.failed(ProcessUnmarshallingError(error))
                            case Right(rawScenarioTestData) =>
                              scenarioTestService
                                .performTest(
                                  idWithName,
                                  displayableProcess,
                                  rawScenarioTestData
                                )
                                .flatMap { results =>
                                  Marshal(results).to[MessageEntity].map(en => HttpResponse(entity = en))
                                }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          } ~
          path("testWithParameters" / ProcessNameSegment) { processName =>
            {
              (post & processDetailsForName(processName)) { process =>
                val modelData = typeToConfig.forTypeUnsafe(process.processingType)
                implicit val requestDecoder: Decoder[TestFromParametersRequest] =
                  prepareTestFromParametersDecoder(modelData)
                (post & entity(as[TestFromParametersRequest])) { testParametersRequest =>
                  {
                    processId(processName) { idWithName =>
                      canDeploy(idWithName) {
                        complete {
                          scenarioTestService
                            .performTest(
                              idWithName,
                              testParametersRequest.displayableProcess,
                              testParametersRequest.sourceParameters
                            )
                            .flatMap { results =>
                              Marshal(results).to[MessageEntity].map(en => HttpResponse(entity = en))
                            }
                        }
                      }
                    }
                  }
                }
              }
            }
          } ~
          path("customAction" / ProcessNameSegment) { processName =>
            (post & processId(processName) & entity(as[CustomActionRequest])) { (process, req) =>
              val params = req.params.getOrElse(Map.empty)
              complete {
                customActionInvokerService
                  .invokeCustomAction(req.actionName, process, params)
                  .flatMap {
                    case res @ Right(_) =>
                      toHttpResponse(CustomActionResponse(res))(StatusCodes.OK)
                    case res @ Left(err) =>
                      val response = toHttpResponse(CustomActionResponse(res)) _
                      err match {
                        case _: CustomActionFailure        => response(StatusCodes.InternalServerError)
                        case _: CustomActionInvalidStatus  => response(StatusCodes.Forbidden)
                        case _: CustomActionForbidden      => response(StatusCodes.Forbidden)
                        case _: CustomActionNotImplemented => response(StatusCodes.NotImplemented)
                        case _: CustomActionNonExisting    => response(StatusCodes.NotFound)
                      }
                  }
              }
            }
          }
      }
  }

  private def toHttpResponse[A: Encoder](a: A)(code: StatusCode): Future[HttpResponse] =
    Marshal(a).to[MessageEntity].map(en => HttpResponse(entity = en, status = code))

  private def convertSavepointResultToResponse(future: Future[SavepointResult]) = {
    future
      .map { case SavepointResult(path) => HttpResponse(entity = path, status = StatusCodes.OK) }
  }

}
