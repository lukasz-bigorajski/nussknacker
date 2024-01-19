package pl.touk.nussknacker.ui.services

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.engine.api.process.{ProcessId, ProcessName}
import pl.touk.nussknacker.restmodel.SecurityError.AuthorizationError
import pl.touk.nussknacker.security.Permission
import pl.touk.nussknacker.security.Permission.Permission
import pl.touk.nussknacker.ui.api.ScenarioActivityApiEndpoints.Dtos._
import pl.touk.nussknacker.ui.api.{AuthorizeProcess, ScenarioActivityApiEndpoints, ScenarioAttachmentService}
import pl.touk.nussknacker.ui.process.repository.{ProcessActivityRepository, UserComment}
import pl.touk.nussknacker.ui.process.{ProcessCategoryService, ProcessService}
import pl.touk.nussknacker.ui.security.api.{AuthenticationResources, LoggedUser}
import pl.touk.nussknacker.ui.util.AkkaToTapirStreamExtension
import pl.touk.nussknacker.ui.util.HeadersSupport.ContentDisposition
import sttp.model.MediaType

import java.io.ByteArrayInputStream
import java.net.URLConnection
import scala.concurrent.{ExecutionContext, Future}

class ScenarioActivityApiHttpService(
    config: Config,
    scenarioCategoryService: () => ProcessCategoryService,
    authenticator: AuthenticationResources,
    scenarioActivityRepository: ProcessActivityRepository,
    scenarioService: ProcessService,
    scenarioAuthorizer: AuthorizeProcess,
    attachmentService: ScenarioAttachmentService,
    implicit val akkaToTapirStreamExtension: AkkaToTapirStreamExtension
)(implicit executionContext: ExecutionContext)
    extends BaseHttpService(config, scenarioCategoryService, authenticator)
    with LazyLogging {

  private val scenarioActivityApiEndpoints = new ScenarioActivityApiEndpoints(authenticator.authenticationMethod())

  expose {
    scenarioActivityApiEndpoints.scenarioActivityEndpoint
      .serverSecurityLogic(authorizeKnownUser[String])
      .serverLogic { implicit loggedUser => scenarioName: ProcessName =>
        checkPermission(scenarioName, Permission.Read) { scenarioId =>
          scenarioActivityRepository
            .findActivity(scenarioId)
            .map(scenarioActivity => success(ScenarioActivity(scenarioActivity)))
        }
      }
  }

  expose {
    scenarioActivityApiEndpoints.addCommentEndpoint
      .serverSecurityLogic(authorizeKnownUser[String])
      .serverLogic { implicit loggedUser => request: AddCommentRequest =>
        checkPermission(request.scenarioName) { scenarioId =>
          scenarioActivityRepository
            .addComment(scenarioId, request.versionId, UserComment(request.commentContent))
            .map(success)
        }
      }
  }

  expose {
    scenarioActivityApiEndpoints.deleteCommentEndpoint
      .serverSecurityLogic(authorizeKnownUser[String])
      .serverLogic { implicit loggedUser => request: DeleteCommentRequest =>
        checkPermission(request.scenarioName) { _ =>
          scenarioActivityRepository
            .deleteComment(request.commentId)
            .map(success)
        }
      }
  }

  expose {
    scenarioActivityApiEndpoints.addAttachmentEndpoint
      .serverSecurityLogic(authorizeKnownUser[String])
      .serverLogic { implicit loggedUser => request: AddAttachmentRequest =>
        checkPermission(request.scenarioName) { scenarioId =>
          attachmentService
            .saveAttachment(scenarioId, request.versionId, request.fileName.value, request.streamBody)
            .map(success)
        }
      }
  }

  expose {
    scenarioActivityApiEndpoints.downloadAttachmentEndpoint
      .serverSecurityLogic(authorizeKnownUser[String])
      .serverLogic { implicit loggedUser => request: GetAttachmentRequest =>
        checkPermission(request.scenarioName, Permission.Read) { scenarioId =>
          attachmentService
            .readAttachment(request.attachmentId, scenarioId)
            .map(_.fold(GetAttachmentResponse.emptyResponse) { case (fileName, content) =>
              GetAttachmentResponse(
                new ByteArrayInputStream(content),
                ContentDisposition.fromFileNameString(fileName).headerValue(),
                Option(URLConnection.guessContentTypeFromName(fileName))
                  .getOrElse(MediaType.ApplicationOctetStream.toString())
              )
            })
            .map(success)
        }
      }
  }

  private def checkPermission[E, R](scenarioName: ProcessName, permission: Permission = Permission.Write)(
      businessLogic: ProcessId => Future[LogicResult[E, R]]
  )(implicit loggedUser: LoggedUser): Future[LogicResult[E, R]] = {
    for {
      scenarioId <- scenarioService.getProcessId(scenarioName)
      canWrite   <- scenarioAuthorizer.check(scenarioId, permission, loggedUser)
      result     <- if (canWrite) businessLogic(scenarioId) else Future.successful(securityError(AuthorizationError))
    } yield result
  }

}
