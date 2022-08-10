package pl.touk.nussknacker.engine.lite.kafka

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.FunSuite
import pl.touk.nussknacker.engine.lite.kafka.NuRuntimeTestUtils.{saveScenarioToTmp, testCaseId}
import pl.touk.nussknacker.engine.lite.kafka.sample.NuReqRespTestSamples.{jsonPingMessage, jsonPongMessage, pingPongScenario}
import pl.touk.nussknacker.test.AvailablePortFinder
import sttp.client.{HttpURLConnectionBackend, Identity, NothingT, SttpBackend, UriContext, basicRequest}

class NuReqRespRuntimeBinTest extends FunSuite with BaseNuRuntimeBinTestMixin with LazyLogging {

  private implicit val backend: SttpBackend[Identity, Nothing, NothingT] = HttpURLConnectionBackend()

  test("binary version should handle ping pong via http") {
    val shellScriptArgs = Array(shellScriptPath.toString, saveScenarioToTmp(pingPongScenario, testCaseId(suiteName, pingPongScenario)).toString, NuRuntimeTestUtils.deploymentDataFile.toString)
    val port = AvailablePortFinder.findAvailablePorts(1).head
    val shellScriptEnvs = Array(
      "CONFIG_FORCE_http_interface=localhost",
      s"CONFIG_FORCE_http_port=$port",
    ) ++ akkaManagementEnvs

    withProcessExecutedInBackground(shellScriptArgs, shellScriptEnvs, {}, {
      eventually { // TODO: check ready probe
        val request = basicRequest.post(uri"http://localhost".port(port).path("scenario", pingPongScenario.id))
        request.body(jsonPingMessage("foo")).send().body shouldBe Right(jsonPongMessage("foo"))
      }
    })
  }

}
