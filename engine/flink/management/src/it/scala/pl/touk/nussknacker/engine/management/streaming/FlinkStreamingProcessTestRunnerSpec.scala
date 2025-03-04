package pl.touk.nussknacker.engine.management.streaming

import akka.actor.ActorSystem
import com.typesafe.config.ConfigValueFactory.fromAnyRef
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import io.circe.Json
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.ConfigWithUnresolvedVersion
import pl.touk.nussknacker.engine.api.Context
import pl.touk.nussknacker.engine.api.deployment.{ProcessingTypeDeploymentService, ProcessingTypeDeploymentServiceStub}
import pl.touk.nussknacker.engine.api.process.ProcessName
import pl.touk.nussknacker.engine.api.test.{ScenarioTestData, ScenarioTestJsonRecord}
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.management.FlinkStreamingDeploymentManagerProvider
import pl.touk.nussknacker.test.{KafkaConfigProperties, VeryPatientScalaFutures}
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import sttp.client3.SttpBackend

import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

class FlinkStreamingProcessTestRunnerSpec extends AnyFlatSpec with Matchers with VeryPatientScalaFutures {

  private implicit val actorSystem: ActorSystem = ActorSystem(getClass.getSimpleName)
  import actorSystem.dispatcher
  implicit val backend: SttpBackend[Future, Any] =
    AsyncHttpClientFutureBackend.usingConfig(new DefaultAsyncHttpClientConfig.Builder().build())
  implicit val deploymentService: ProcessingTypeDeploymentService = new ProcessingTypeDeploymentServiceStub(List.empty)

  private val classPath: List[String] = ClassPaths.scalaClasspath

  private val config = ConfigWithUnresolvedVersion(
    ConfigFactory
      .load()
      .withValue("deploymentConfig.restUrl", fromAnyRef(s"http://dummy:1234"))
      .withValue(
        KafkaConfigProperties.bootstrapServersProperty("modelConfig.kafka"),
        ConfigValueFactory.fromAnyRef("kafka:1234")
      )
      .withValue("modelConfig.classPath", ConfigValueFactory.fromIterable(classPath.asJava))
  )

  private val scenarioTestData = ScenarioTestData(
    List(ScenarioTestJsonRecord("startProcess", Json.fromString("terefere")))
  )

  it should "run scenario in test mode" in {
    val deploymentManager = FlinkStreamingDeploymentManagerProvider.defaultDeploymentManager(config)

    val processName = ProcessName(UUID.randomUUID().toString)

    val process = SampleProcess.prepareProcess(processName)

    whenReady(deploymentManager.test(processName, process, scenarioTestData)) { r =>
      r.nodeResults shouldBe Map(
        "startProcess" -> List(Context(s"$processName-startProcess-0-0", Map("input" -> "terefere"))),
        "nightFilter"  -> List(Context(s"$processName-startProcess-0-0", Map("input" -> "terefere"))),
        "endSend"      -> List(Context(s"$processName-startProcess-0-0", Map("input" -> "terefere")))
      )
    }
  }

  it should "return correct error messages" in {
    val processId = UUID.randomUUID().toString

    val process = ScenarioBuilder
      .streaming(processId)
      .source("startProcess", "kafka-transaction")
      .emptySink("endSend", "sendSmsNotExist")

    val deploymentManager = FlinkStreamingDeploymentManagerProvider.defaultDeploymentManager(config)

    val caught = intercept[IllegalArgumentException] {
      Await.result(
        deploymentManager.test(ProcessName(processId), process, scenarioTestData),
        patienceConfig.timeout
      )
    }
    caught.getMessage shouldBe "Compilation errors: MissingSinkFactory(sendSmsNotExist,endSend)"
  }

}
