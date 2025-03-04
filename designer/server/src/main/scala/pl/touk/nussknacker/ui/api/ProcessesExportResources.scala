package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.Materializer
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter
import pl.touk.nussknacker.ui.process.repository.DbProcessActivityRepository.ProcessActivity
import pl.touk.nussknacker.ui.process.repository.{
  FetchingProcessRepository,
  ProcessActivityRepository,
  ScenarioWithDetailsEntity
}
import pl.touk.nussknacker.ui.security.api.LoggedUser
import pl.touk.nussknacker.ui.uiresolving.UIProcessResolver
import pl.touk.nussknacker.ui.util._
import io.circe.syntax._
import pl.touk.nussknacker.engine.api.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.ui.process.ProcessService
import pl.touk.nussknacker.ui.process.processingtypedata.ProcessingTypeDataProvider

import scala.concurrent.{ExecutionContext, Future}

class ProcessesExportResources(
    processRepository: FetchingProcessRepository[Future],
    protected val processService: ProcessService,
    processActivityRepository: ProcessActivityRepository,
    processResolvers: ProcessingTypeDataProvider[UIProcessResolver, _]
)(implicit val ec: ExecutionContext, mat: Materializer)
    extends Directives
    with FailFastCirceSupport
    with RouteWithUser
    with ProcessDirectives
    with NuPathMatchers {

  private implicit final val string: FromEntityUnmarshaller[String] =
    Unmarshaller.stringUnmarshaller.forContentTypes(ContentTypeRange.*)

  def securedRoute(implicit user: LoggedUser): Route = {
    path("processesExport" / ProcessNameSegment) { processName =>
      (get & processId(processName)) { processId =>
        complete {
          processRepository.fetchLatestProcessDetailsForProcessId[DisplayableProcess](processId.id).map {
            exportProcess
          }
        }
      }
    } ~ path("processesExport" / ProcessNameSegment / VersionIdSegment) { (processName, versionId) =>
      (get & processId(processName)) { processId =>
        complete {
          processRepository.fetchProcessDetailsForId[DisplayableProcess](processId.id, versionId).map {
            exportProcess
          }
        }
      }
    } ~ path("processesExport" / "pdf" / ProcessNameSegment / VersionIdSegment) { (processName, versionId) =>
      (post & processId(processName)) { processId =>
        entity(as[String]) { svg =>
          complete {
            processRepository.fetchProcessDetailsForId[DisplayableProcess](processId.id, versionId).flatMap { process =>
              processActivityRepository.findActivity(processId.id).map(exportProcessToPdf(svg, process, _))
            }
          }
        }
      }
    } ~ path("processesExport") {
      post {
        entity(as[DisplayableProcess]) { process =>
          complete {
            exportResolvedProcess(process)
          }
        }
      }
    }
  }

  private def exportProcess(processDetails: Option[ScenarioWithDetailsEntity[DisplayableProcess]]): HttpResponse =
    processDetails.map(_.json) match {
      case Some(displayableProcess) =>
        exportProcess(displayableProcess)
      case None =>
        HttpResponse(status = StatusCodes.NotFound, entity = "Scenario not found")
    }

  private def exportProcess(processDetails: DisplayableProcess): HttpResponse = {
    fileResponse(ProcessConverter.fromDisplayable(processDetails))
  }

  private def exportResolvedProcess(
      processWithDictLabels: DisplayableProcess
  )(implicit user: LoggedUser): HttpResponse = {
    val processResolver = processResolvers.forTypeUnsafe(processWithDictLabels.processingType)
    val resolvedProcess = processResolver.validateAndResolve(processWithDictLabels)
    fileResponse(resolvedProcess)
  }

  private def fileResponse(canonicalProcess: CanonicalProcess) = {
    val canonicalJson = canonicalProcess.asJson.spaces2
    val entity        = HttpEntity(ContentTypes.`application/json`, canonicalJson)
    AkkaHttpResponse.asFile(entity, s"${canonicalProcess.name}.json")
  }

  private def exportProcessToPdf(
      svg: String,
      processDetails: Option[ScenarioWithDetailsEntity[DisplayableProcess]],
      processActivity: ProcessActivity
  ) = processDetails match {
    case Some(process) =>
      val pdf = PdfExporter.exportToPdf(svg, process, processActivity)
      HttpResponse(status = StatusCodes.OK, entity = HttpEntity(pdf))
    case None =>
      HttpResponse(status = StatusCodes.NotFound, entity = "Scenario not found")
  }

}
