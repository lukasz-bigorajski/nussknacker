package pl.touk.nussknacker.ui.api

import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.IOUtils
import org.apache.commons.io.input.BoundedInputStream
import pl.touk.nussknacker.engine.api.process.{ProcessId, VersionId}
import pl.touk.nussknacker.ui.api.ScenarioAttachmentService.{AttachmentDataWithName, AttachmentToAdd}
import pl.touk.nussknacker.ui.config.AttachmentsConfig
import pl.touk.nussknacker.ui.process.repository.ProcessActivityRepository
import pl.touk.nussknacker.ui.security.api.LoggedUser
import pl.touk.nussknacker.ui.util.CatsSyntax

import java.io.InputStream
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using

class ScenarioAttachmentService(config: AttachmentsConfig, scenarioActivityRepository: ProcessActivityRepository)
    extends LazyLogging {

  def saveAttachment(
      processId: ProcessId,
      processVersionId: VersionId,
      originalFileName: String,
      inputStream: InputStream
  )(implicit ec: ExecutionContext, loggedUser: LoggedUser): Future[Unit] = {
    Future
      .successful(new BoundedInputStream(inputStream, config.maxSizeInBytes + 1))
      .flatMap(is => {
        Using.resource(is) { isResource =>
          val bytes = IOUtils.toByteArray(isResource)
          if (bytes.length > config.maxSizeInBytes) {
            Future.failed(
              new IllegalArgumentException(s"Maximum (${config.maxSizeInBytes} bytes) attachment size exceeded.")
            )
          } else {
            scenarioActivityRepository.addAttachment(
              AttachmentToAdd(processId, processVersionId, originalFileName, bytes)
            )
          }
        }
      })
  }

  def readAttachment(
      attachmentId: Long,
      scenarioId: ProcessId
  )(implicit ec: ExecutionContext): Future[Option[AttachmentDataWithName]] = {
    val attachmentFutureOpt = scenarioActivityRepository.findAttachment(attachmentId, scenarioId)
    CatsSyntax.futureOpt.map(attachmentFutureOpt) { attachment =>
      (attachment.fileName, attachment.data)
    }
  }

}

object ScenarioAttachmentService {
  type AttachmentDataWithName = (String, Array[Byte])

  final case class AttachmentToAdd(
      processId: ProcessId,
      processVersionId: VersionId,
      fileName: String,
      data: Array[Byte]
  )

}
