package pl.touk.nussknacker.k8s.manager

import akka.actor.ActorSystem
import akka.stream.scaladsl.TcpIdleTimeoutException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Span.convertSpanToDuration
import org.scalatest.{BeforeAndAfterAll, Inside, OptionValues}
import pl.touk.nussknacker.engine.api.process.ProcessName
import pl.touk.nussknacker.engine.testing.LocalModelData
import pl.touk.nussknacker.test.{AvailablePortFinder, PatientScalaFutures}
import skuber.api.Configuration

import scala.concurrent.duration._

class K8sDeploymentManagerOnMocksTest
    extends AnyFunSuite
    with BeforeAndAfterAll
    with PatientScalaFutures
    with Inside
    with Matchers
    with OptionValues {

  protected implicit val system: ActorSystem = ActorSystem(getClass.getSimpleName)
  import system.dispatcher

  private var wireMockServer: WireMockServer = _

  test("return process state respecting a short timeout for this operation") {
    def stubWithFixedDelay(delay: FiniteDuration): Unit = {
      wireMockServer.stubFor(
        get(urlPathEqualTo("/apis/apps/v1/namespaces/default/deployments")).willReturn(
          aResponse()
            .withBody("""{
                |  "apiVersion": "v1",
                |  "kind": "DeploymentList",
                |  "items": []
                |}""".stripMargin)
            .withHeader("Content-Type", "application/json")
            .withFixedDelay(delay.toMillis.toInt)
        )
      )
      wireMockServer.stubFor(
        get(urlPathEqualTo("/api/v1/namespaces/default/pods")).willReturn(
          aResponse()
            .withBody("""{
                |  "apiVersion": "v1",
                |  "kind": "PodList",
                |  "items": []
                |}""".stripMargin)
            .withHeader("Content-Type", "application/json")
        )
      )
    }
    val clientIdleTimeout = 1.second
    val k8sConfig         = K8sDeploymentManagerConfig(scenarioStateIdleTimeout = clientIdleTimeout)
    val manager = new K8sDeploymentManager(
      LocalModelData(ConfigFactory.empty, List.empty),
      k8sConfig,
      ConfigFactory.empty()
    ) {
      override protected def k8sConfiguration: Configuration = Configuration.useLocalProxyOnPort(wireMockServer.port())
    }

    val durationLongerThanClientTimeout = clientIdleTimeout.plus(patienceConfig.timeout)
    stubWithFixedDelay(durationLongerThanClientTimeout)
    a[TcpIdleTimeoutException] shouldBe thrownBy {
      manager
        .getFreshProcessStates(ProcessName("foo"))
        .futureValueEnsuringInnerException(durationLongerThanClientTimeout)
    }

    stubWithFixedDelay(0 seconds)
    val result = manager
      .getFreshProcessStates(ProcessName("foo"))
      .futureValueEnsuringInnerException(durationLongerThanClientTimeout)
    result shouldEqual List.empty
  }

  override protected def beforeAll(): Unit = {
    wireMockServer = AvailablePortFinder.withAvailablePortsBlocked(1)(l => new WireMockServer(l.head))
    wireMockServer.start()
  }

  override protected def afterAll(): Unit = {
    system.terminate().futureValue
    wireMockServer.stop()
  }

}
