package pl.touk.nussknacker.ui.statistics

import db.util.DBIOActionInstances.DB
import org.apache.commons.io.FileUtils
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.OptionValues
import org.scalatest.funsuite.{AnyFunSuite, AnyFunSuiteLike}
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import pl.touk.nussknacker.test.PatientScalaFutures
import pl.touk.nussknacker.ui.config.UsageStatisticsReportsConfig
import pl.touk.nussknacker.ui.process.repository.DBIOActionRunner
import pl.touk.nussknacker.ui.statistics.repository.FingerprintRepository
import slick.dbio.DBIOAction

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FingerprintServiceTest extends AnyFunSuite with Matchers with OptionValues with PatientScalaFutures {

  private val dbioActionRunner = mock[DBIOActionRunner]
  private val repository       = mock[FingerprintRepository[DB]]
  private val sut              = new FingerprintService(dbioActionRunner, repository)

//  val fingerprintFile = getTempFileLocation
//  fingerprintFile.exists() shouldBe false

  test("should generate a random fingerprint if the configured is blank") {
    when(repository.read()).thenReturn(DBIOAction.successful(None))
    when(dbioActionRunner.run(any[DBIOAction[Option[String]]]))
    val fingerprint = sut.fingerprint(config).futureValue
    fingerprint.value shouldBe "set via config"
  }

  test("should return fingerprint from configuration") {
    val config      = UsageStatisticsReportsConfig(enabled = true, Some("set via config"), None)
    val fingerprint = sut.fingerprint(config).futureValue
    fingerprint.value shouldBe "set via config"
  }

//  test("should read persisted fingerprint") {
//    val fingerprintFile = File.createTempFile("nussknacker", ".fingerprint")
//    fingerprintFile.deleteOnExit()
//    val savedFingerprint = "foobarbaz123"
//    FileUtils.writeStringToFile(fingerprintFile, savedFingerprint, StandardCharsets.UTF_8)
//
//    val params = new UsageStatisticsReportsSettingsDeterminer(
//      UsageStatisticsReportsConfig(enabled = true, None, None),
//      _,
//      () => Future.successful(List.empty)
//    ).determineQueryParams().futureValue
//    params.get("fingerprint").value shouldEqual savedFingerprint
//  }

  private val config = UsageStatisticsReportsConfig(enabled = true, None, None)

  private val fingerprintFile = getTempFileLocation

  private def getTempFileLocation: File = {
    val file = new File(System.getProperty("java.io.tmpdir"), s"nussknacker-${UUID.randomUUID()}.fingerprint")
    file.deleteOnExit()
    file
  }

}
