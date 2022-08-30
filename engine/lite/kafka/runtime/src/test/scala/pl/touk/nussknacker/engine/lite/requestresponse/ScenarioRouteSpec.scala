package pl.touk.nussknacker.engine.lite.requestresponse

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.ProcessVersion
import pl.touk.nussknacker.engine.api.process.{ComponentUseCase, EmptyProcessConfigCreator, ProcessName}
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.lite.api.runtimecontext.LiteEngineRuntimeContextPreparer
import pl.touk.nussknacker.engine.lite.components.requestresponse.jsonschema.sinks.JsonRequestResponseSink.SinkRawEditorParamName
import pl.touk.nussknacker.engine.requestresponse.FutureBasedRequestResponseScenarioInterpreter._
import pl.touk.nussknacker.engine.requestresponse.RequestResponseInterpreter
import pl.touk.nussknacker.engine.requestresponse.api.openapi.RequestResponseOpenApiSettings.{InputSchemaProperty, OutputSchemaProperty}
import pl.touk.nussknacker.engine.requestresponse.openapi.OApiServer
import pl.touk.nussknacker.engine.resultcollector.ProductionServiceInvocationCollector
import pl.touk.nussknacker.engine.spel
import pl.touk.nussknacker.engine.testing.LocalModelData

import scala.concurrent.Future

class ScenarioRouteSpec extends AnyFlatSpec with ScalatestRouteTest with Matchers {

  import spel.Implicits._

  private val inputSchema = """{"type" : "object", "properties": {"city": {"type": "string", "default": "Warsaw"}}}"""
  private val outputSchema = """{"type" : "object", "properties": {"place": {"type": "string"}}}"""
  private val process = ScenarioBuilder
    .requestResponse("test")
    .additionalFields(description = Some("description"), properties = Map(InputSchemaProperty -> inputSchema, OutputSchemaProperty -> outputSchema))
    .source("start", "request")
    .emptySink("end", "response", SinkRawEditorParamName -> "false", "place" -> "#input.city")

  private val modelData = LocalModelData(ConfigFactory.load(), new EmptyProcessConfigCreator)

  private val interpreter = RequestResponseInterpreter[Future](
    process,
    ProcessVersion.empty.copy(processName = ProcessName(process.metaData.id)),
    LiteEngineRuntimeContextPreparer.noOp,
    modelData, Nil, ProductionServiceInvocationCollector, ComponentUseCase.EngineRuntime)
    .valueOr(errors => throw new IllegalArgumentException(s"Failed to compile: $errors"))

  private val scenarioRoute = new ScenarioRoute(Map("test" -> new RequestResponseAkkaHttpHandler(interpreter)), OpenApiDefinitionConfig(
    server = Some(OApiServer("https://nussknacker.io", "request response test"))
  ))

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    interpreter.open()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    interpreter.close()
  }

  it should "get scenario openapi definition" in {
    Get(s"/scenario/test/definition") ~> scenarioRoute.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldBe expectedOApiDef
    }
  }

  it should "handle post" in {
    val msg = """{"city":"London"}"""
    Post(s"/scenario/test", HttpEntity(ContentTypes.`application/json`, msg)) ~> scenarioRoute.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldBe s"""{"place":"London"}"""
    }
  }

  private val expectedOApiDef =
    """{
      |  "openapi" : "3.1.0",
      |  "info" : {
      |    "title" : "test",
      |    "description" : "description",
      |    "version" : "1"
      |  },
      |  "servers" : [
      |    {
      |      "url" : "https://nussknacker.io",
      |      "description" : "request response test"
      |    }
      |  ],
      |  "paths" : {
      |    "/test" : {
      |      "post" : {
      |        "description" : "**scenario name**: test",
      |        "tags" : [
      |          "Nussknacker"
      |        ],
      |        "requestBody" : {
      |          "required" : true,
      |          "content" : {
      |            "application/json" : {
      |              "schema" : {
      |                "type" : "object",
      |                "nullable" : false,
      |                "properties" : {
      |                  "city" : {
      |                    "type" : "string",
      |                    "nullable" : false,
      |                    "default" : "Warsaw"
      |                  }
      |                }
      |              }
      |            }
      |          }
      |        },
      |        "produces" : [
      |          "application/json"
      |        ],
      |        "consumes" : [
      |          "application/json"
      |        ],
      |        "summary" : "test",
      |        "responses" : {
      |          "200" : {
      |            "content" : {
      |              "application/json" : {
      |                "schema" : {
      |                  "type" : "object",
      |                  "properties" : {
      |                    "place" : {
      |                      "type" : "string"
      |                    }
      |                  }
      |                }
      |              }
      |            }
      |          }
      |        }
      |      }
      |    }
      |  }
      |}""".stripMargin
}

