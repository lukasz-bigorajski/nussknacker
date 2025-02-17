package pl.touk.nussknacker.ui.api.helpers

import _root_.sttp.client3.SttpBackend
import _root_.sttp.client3.akkahttp.AkkaHttpBackend
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import cats.instances.all._
import cats.syntax.semigroup._
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import db.util.DBIOActionInstances.DB
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json, parser}
import io.dropwizard.metrics5.MetricRegistry
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterEach, OptionValues, Suite}
import pl.touk.nussknacker.engine._
import pl.touk.nussknacker.engine.api.CirceUtil.humanReadablePrinter
import pl.touk.nussknacker.engine.api.deployment._
import pl.touk.nussknacker.engine.api.deployment.simple.SimpleStateStatus
import pl.touk.nussknacker.engine.api.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.engine.api.process.{ProcessId, ProcessName, VersionId}
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.definition.test.{ModelDataTestInfoProvider, TestInfoProvider}
import pl.touk.nussknacker.engine.management.FlinkStreamingDeploymentManagerProvider
import pl.touk.nussknacker.engine.api.process.ProcessingType
import pl.touk.nussknacker.restmodel.scenariodetails.ScenarioWithDetails
import pl.touk.nussknacker.restmodel.CustomActionRequest
import pl.touk.nussknacker.test.EitherValuesDetailedMessage
import pl.touk.nussknacker.ui.api._
import pl.touk.nussknacker.ui.api.helpers.TestFactory._
import pl.touk.nussknacker.ui.config.FeatureTogglesConfig
import pl.touk.nussknacker.ui.process.ProcessService.UpdateProcessCommand
import pl.touk.nussknacker.ui.process._
import pl.touk.nussknacker.ui.process.deployment._
import pl.touk.nussknacker.ui.process.fragment.DefaultFragmentRepository
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter
import pl.touk.nussknacker.ui.process.processingtypedata.{
  DefaultProcessingTypeDeploymentService,
  ProcessingTypeDataProvider,
  ProcessingTypeDataReader,
  ProcessingTypeDataState
}
import pl.touk.nussknacker.ui.process.repository.ProcessRepository.CreateProcessAction
import pl.touk.nussknacker.ui.process.repository._
import pl.touk.nussknacker.ui.process.test.{PreliminaryScenarioTestDataSerDe, ScenarioTestService}
import pl.touk.nussknacker.ui.processreport.ProcessCounter
import pl.touk.nussknacker.ui.security.api.LoggedUser
import pl.touk.nussknacker.ui.util.{ConfigWithScalaVersion, MultipartUtils}
import slick.dbio.DBIOAction

import java.net.URI
import scala.concurrent.{ExecutionContext, Future}

// TODO: Consider using NuItTest with NuScenarioConfigurationHelper instead. This one will be removed in the future.
trait NuResourcesTest
    extends WithHsqlDbTesting
    with EitherValuesDetailedMessage
    with OptionValues
    with TestPermissions
    with NuScenarioConfigurationHelper
    with BeforeAndAfterEach
    with LazyLogging {
  self: ScalatestRouteTest with Suite with Matchers with ScalaFutures =>

  import ProcessesQueryEnrichments.RichProcessesQuery
  import TestCategories._
  import TestProcessingTypes._

  private implicit val sttpBackend: SttpBackend[Future, Any] = AkkaHttpBackend.usingActorSystem(system)

  protected val adminUser: LoggedUser = TestFactory.adminUser("user")

  private implicit val implicitAdminUser: LoggedUser = adminUser

  protected val dbioRunner: DBIOActionRunner = newDBIOActionRunner(testDbRef)

  protected val fetchingProcessRepository: DBFetchingProcessRepository[DB] = newFetchingProcessRepository(testDbRef)

  protected val processAuthorizer: AuthorizeProcess = new AuthorizeProcess(futureFetchingProcessRepository)

  protected val writeProcessRepository: DBProcessRepository = newWriteProcessRepository(testDbRef)

  protected val fragmentRepository: DefaultFragmentRepository = newFragmentRepository(testDbRef)

  protected val actionRepository: DbProcessActionRepository[DB] = newActionProcessRepository(testDbRef)

  protected val processActivityRepository: DbProcessActivityRepository = newProcessActivityRepository(testDbRef)

  protected val processChangeListener = new TestProcessChangeListener()

  protected lazy val deploymentManager: MockDeploymentManager = createDeploymentManager()

  protected val dmDispatcher = new DeploymentManagerDispatcher(
    mapProcessingTypeDataProvider(TestProcessingTypes.Streaming -> deploymentManager),
    futureFetchingProcessRepository
  )

  protected implicit val deploymentService: DeploymentService =
    new DeploymentServiceImpl(
      dmDispatcher,
      fetchingProcessRepository,
      actionRepository,
      dbioRunner,
      processValidatorByProcessingType,
      scenarioResolver,
      processChangeListener,
      None
    )

  private implicit val processingTypeDeploymentService: DefaultProcessingTypeDeploymentService =
    new DefaultProcessingTypeDeploymentService(Streaming, deploymentService)

  protected val processingTypeConfig: ProcessingTypeConfig =
    ProcessingTypeConfig.read(ConfigWithScalaVersion.StreamingProcessTypeConfig)

  protected val deploymentManagerProvider: FlinkStreamingDeploymentManagerProvider =
    new FlinkStreamingDeploymentManagerProvider {

      override def createDeploymentManager(modelData: BaseModelData, config: Config)(
          implicit ec: ExecutionContext,
          actorSystem: ActorSystem,
          sttpBackend: SttpBackend[Future, Any],
          deploymentService: ProcessingTypeDeploymentService
      ): DeploymentManager = deploymentManager

    }

  protected val testModelDataProvider: ProcessingTypeDataProvider[ModelData, _] = mapProcessingTypeDataProvider(
    Streaming  -> ModelData(processingTypeConfig),
    Streaming2 -> ModelData(processingTypeConfig)
  )

  protected val testProcessingTypeDataProvider: ProcessingTypeDataProvider[ProcessingTypeData, _] =
    mapProcessingTypeDataProvider(
      Streaming  -> ProcessingTypeData.createProcessingTypeData(deploymentManagerProvider, processingTypeConfig),
      Streaming2 -> ProcessingTypeData.createProcessingTypeData(deploymentManagerProvider, processingTypeConfig)
    )

  protected val newProcessPreparer: NewProcessPreparer = createNewProcessPreparer()

  protected val featureTogglesConfig: FeatureTogglesConfig = FeatureTogglesConfig.create(testConfig)

  protected val typeToConfig: ProcessingTypeDataProvider[ProcessingTypeData, _] =
    ProcessingTypeDataProvider(ProcessingTypeDataReader.loadProcessingTypeData(ConfigWithUnresolvedVersion(testConfig)))

  protected val customActionInvokerService =
    new CustomActionInvokerServiceImpl(futureFetchingProcessRepository, dmDispatcher, deploymentService)

  protected val testExecutorService = new ScenarioTestExecutorServiceImpl(scenarioResolver, dmDispatcher)

  protected val processService: DBProcessService = createDBProcessService(deploymentService)

  protected val scenarioTestService: ScenarioTestService = createScenarioTestService(
    testModelDataProvider.mapValues(new ModelDataTestInfoProvider(_))
  )

  protected val configProcessToolbarService =
    new ConfigProcessToolbarService(testConfig, () => processCategoryService.getAllCategories)

  protected val processesRoute = new ProcessesResources(
    processService = processService,
    deploymentService = deploymentService,
    processToolbarService = configProcessToolbarService,
    processAuthorizer = processAuthorizer,
    processChangeListener = processChangeListener
  )

  protected val processActivityRoute =
    new ProcessActivityResource(processActivityRepository, processService, processAuthorizer)

  protected val processActivityRouteWithAllPermissions: Route = withAllPermissions(processActivityRoute)

  protected val processesRouteWithAllPermissions: Route = withAllPermissions(processesRoute)

  override def testConfig: Config = ConfigWithScalaVersion.TestsConfig

  protected def createDBProcessService(deploymentService: DeploymentService): DBProcessService =
    new DBProcessService(
      deploymentService,
      newProcessPreparer,
      () => processCategoryService,
      processResolverByProcessingType,
      dbioRunner,
      futureFetchingProcessRepository,
      actionRepository,
      writeProcessRepository
    )

  protected def createScenarioTestService(
      testInfoProviders: ProcessingTypeDataProvider[TestInfoProvider, _]
  ): ScenarioTestService =
    new ScenarioTestService(
      testInfoProviders,
      featureTogglesConfig.testDataSettings,
      new PreliminaryScenarioTestDataSerDe(featureTogglesConfig.testDataSettings),
      processResolverByProcessingType,
      new ProcessCounter(TestFactory.prepareSampleFragmentRepository),
      testExecutorService
    )

  protected def deployRoute(deploymentCommentSettings: Option[DeploymentCommentSettings] = None) =
    new ManagementResources(
      processAuthorizer = processAuthorizer,
      processService = processService,
      deploymentCommentSettings = deploymentCommentSettings,
      deploymentService = deploymentService,
      dispatcher = dmDispatcher,
      customActionInvokerService = customActionInvokerService,
      metricRegistry = new MetricRegistry,
      scenarioTestService = scenarioTestService,
      typeToConfig = typeToConfig.mapValues(_.modelData)
    )

  protected def createDeploymentManager(): MockDeploymentManager = new MockDeploymentManager(
    SimpleStateStatus.NotDeployed
  )(new ProcessingTypeDeploymentServiceStub(Nil))

  override def beforeEach(): Unit = {
    super.beforeEach()
    processChangeListener.clear()
  }

  protected def saveProcessAndAssertSuccess(
      processName: ProcessName,
      process: CanonicalProcess,
      category: String = Category1
  ): Assertion =
    saveProcess(processName, process, category) {
      status shouldEqual StatusCodes.OK
    }

  protected def saveProcess(processName: ProcessName, process: CanonicalProcess, category: String)(
      testCode: => Assertion
  ): Assertion =
    createProcessRequest(processName, category) { _ =>
      val json = parser.decode[Json](responseAs[String]).rightValue
      val resp = CreateProcessResponse(json)

      resp.processName shouldBe processName

      updateProcess(processName, process)(testCode)
    }

  protected def saveProcess(process: DisplayableProcess)(testCode: => Assertion): Assertion = {
    createProcessRequest(process.name, process.category) { code =>
      code shouldBe StatusCodes.Created
      updateProcess(process)(testCode)
    }
  }

  protected def createProcessRequest(processName: ProcessName, category: String = Category1)(
      callback: StatusCode => Assertion
  ): Assertion =
    Post(s"/processes/$processName/$category?isFragment=false") ~> processesRouteWithAllPermissions ~> check {
      callback(status)
    }

  protected def savefragment(process: CanonicalProcess)(testCode: => Assertion): Assertion = {
    val displayable = ProcessConverter.toDisplayable(process, TestProcessingTypes.Streaming, Category1)
    savefragment(displayable)(testCode)
  }

  protected def savefragment(process: DisplayableProcess)(testCode: => Assertion): Assertion = {
    Post(
      s"/processes/${process.name}/${process.category}?isFragment=true"
    ) ~> processesRouteWithAllPermissions ~> check {
      status shouldBe StatusCodes.Created
      updateProcess(process)(testCode)
    }
  }

  protected def updateProcess(process: DisplayableProcess)(testCode: => Assertion): Assertion =
    Put(
      s"/processes/${process.name}",
      TestFactory.posting.toEntityAsProcessToSave(process)
    ) ~> processesRouteWithAllPermissions ~> check {
      testCode
    }

  protected def updateProcess(process: UpdateProcessCommand)(testCode: => Assertion): Assertion =
    Put(
      s"/processes/${process.process.name}",
      TestFactory.posting.toEntity(process)
    ) ~> processesRouteWithAllPermissions ~> check {
      testCode
    }

  protected def updateProcessAndAssertSuccess(processName: ProcessName, process: CanonicalProcess): Assertion =
    updateProcess(processName, process) {
      status shouldEqual StatusCodes.OK
    }

  protected def updateProcess(processName: ProcessName, process: CanonicalProcess, comment: String = "")(
      testCode: => Assertion
  ): Assertion =
    Put(
      s"/processes/$processName",
      TestFactory.posting.toEntityAsProcessToSave(process, comment)
    ) ~> processesRouteWithAllPermissions ~> check {
      testCode
    }

  protected def deployProcess(
      processName: ProcessName,
      deploymentCommentSettings: Option[DeploymentCommentSettings] = None,
      comment: Option[String] = None
  ): RouteTestResult =
    Post(
      s"/processManagement/deploy/$processName",
      HttpEntity(ContentTypes.`application/json`, comment.getOrElse(""))
    ) ~>
      withPermissions(deployRoute(deploymentCommentSettings), testPermissionDeploy |+| testPermissionRead)

  protected def cancelProcess(
      processName: ProcessName,
      deploymentCommentSettings: Option[DeploymentCommentSettings] = None,
      comment: Option[String] = None
  ): RouteTestResult =
    Post(
      s"/processManagement/cancel/$processName",
      HttpEntity(ContentTypes.`application/json`, comment.getOrElse(""))
    ) ~>
      withPermissions(deployRoute(deploymentCommentSettings), testPermissionDeploy |+| testPermissionRead)

  protected def snapshot(processName: ProcessName): RouteTestResult =
    Post(s"/adminProcessManagement/snapshot/$processName") ~> withPermissions(
      deployRoute(),
      testPermissionDeploy |+| testPermissionRead
    )

  protected def stop(processName: ProcessName): RouteTestResult =
    Post(s"/adminProcessManagement/stop/$processName") ~> withPermissions(
      deployRoute(),
      testPermissionDeploy |+| testPermissionRead
    )

  protected def customAction(processName: ProcessName, reqPayload: CustomActionRequest): RouteTestResult =
    Post(s"/processManagement/customAction/$processName", TestFactory.posting.toRequest(reqPayload)) ~>
      withPermissions(deployRoute(), testPermissionDeploy |+| testPermissionRead)

  protected def testScenario(scenario: CanonicalProcess, testDataContent: String): RouteTestResult = {
    val displayableProcess = ProcessConverter.toDisplayable(scenario, TestProcessingTypes.Streaming, Category1)
    val multiPart = MultipartUtils.prepareMultiParts(
      "testData"    -> testDataContent,
      "processJson" -> displayableProcess.asJson.noSpaces
    )()
    Post(s"/processManagement/test/${scenario.name}", multiPart) ~> withPermissions(
      deployRoute(),
      testPermissionDeploy |+| testPermissionRead
    )
  }

  protected def getProcess(processName: ProcessName): RouteTestResult =
    Get(s"/processes/$processName") ~> withPermissions(processesRoute, testPermissionRead)

  protected def getActivity(processName: ProcessName): RouteTestResult =
    Get(s"/processes/$processName/activity") ~> processActivityRouteWithAllPermissions

  protected def forScenarioReturned(processName: ProcessName, isAdmin: Boolean = false)(
      callback: ProcessJson => Unit
  ): Unit =
    tryForScenarioReturned(processName, isAdmin) { (status, response) =>
      status shouldEqual StatusCodes.OK
      val process = decodeJsonProcess(response)
      callback(process)
    }

  protected def tryForScenarioReturned(processName: ProcessName, isAdmin: Boolean = false)(
      callback: (StatusCode, String) => Unit
  ): Unit =
    Get(s"/processes/$processName") ~> routeWithPermissions(processesRoute, isAdmin) ~> check {
      callback(status, responseAs[String])
    }

  protected def forScenariosReturned(query: ScenarioQuery, isAdmin: Boolean = false)(
      callback: List[ProcessJson] => Assertion
  ): Assertion = {
    implicit val basicProcessesUnmarshaller: FromEntityUnmarshaller[List[ScenarioWithDetails]] =
      FailFastCirceSupport.unmarshaller(implicitly[Decoder[List[ScenarioWithDetails]]])
    val url = query.createQueryParamsUrl("/processes")

    Get(url) ~> routeWithPermissions(processesRoute, isAdmin) ~> check {
      status shouldEqual StatusCodes.OK
      val processes = parseResponseToListJsonProcess(responseAs[String])
      responseAs[List[ScenarioWithDetails]] // just to test if decoder succeds
      callback(processes)
    }
  }

  protected def forScenariosDetailsReturned(query: ScenarioQuery, isAdmin: Boolean = false)(
      callback: List[ScenarioWithDetails] => Assertion
  ): Assertion = {
    import FailFastCirceSupport._

    val url = query.createQueryParamsUrl("/processesDetails")

    Get(url) ~> routeWithPermissions(processesRoute, isAdmin) ~> check {
      status shouldEqual StatusCodes.OK
      val processes = responseAs[List[ScenarioWithDetails]]
      callback(processes)
    }
  }

  protected def routeWithPermissions(route: RouteWithUser, isAdmin: Boolean = false): Route =
    if (isAdmin) withAdminPermissions(route) else withAllPermissions(route)

  protected def toEntity[T: Encoder](data: T): HttpEntity.Strict = toEntity(implicitly[Encoder[T]].apply(data))

  private def toEntity(json: Json) = {
    val jsonString = json.printWith(humanReadablePrinter)
    HttpEntity(ContentTypes.`application/json`, jsonString)
  }

  private def prepareValidProcess(
      processName: ProcessName,
      category: String,
      isFragment: Boolean
  ): Future[ProcessId] = {
    val validProcess: CanonicalProcess = if (isFragment) SampleFragment.fragment else SampleProcess.process
    val withNameSet = validProcess.copy(metaData = validProcess.metaData.copy(id = processName.value))
    saveAndGetId(withNameSet, category, isFragment)
  }

  private def prepareEmptyProcess(
      processName: ProcessName,
      category: String,
      isFragment: Boolean
  ): Future[ProcessId] = {
    val emptyProcess = newProcessPreparer.prepareEmptyProcess(processName, Streaming, isFragment)
    saveAndGetId(emptyProcess, category, isFragment)
  }

  private def saveAndGetId(
      process: CanonicalProcess,
      category: String,
      isFragment: Boolean,
      processingType: ProcessingType = Streaming
  ): Future[ProcessId] = {
    val processName = process.name
    val action =
      CreateProcessAction(processName, category, process, processingType, isFragment, forwardedUserName = None)
    for {
      _  <- dbioRunner.runInTransaction(writeProcessRepository.saveNewProcess(action))
      id <- futureFetchingProcessRepository.fetchProcessId(processName).map(_.get)
    } yield id
  }

  protected def getProcessDetails(processId: ProcessId): ScenarioWithDetailsEntity[Unit] =
    futureFetchingProcessRepository.fetchLatestProcessDetailsForProcessId[Unit](processId).futureValue.get

  protected def createEmptyProcess(
      processName: ProcessName,
      category: String = Category1,
      isFragment: Boolean = false
  ): ProcessId =
    prepareEmptyProcess(processName, category, isFragment).futureValue

  protected def createValidProcess(
      processName: ProcessName,
      category: String = Category1,
      isFragment: Boolean = false
  ): ProcessId =
    prepareValidProcess(processName, category, isFragment).futureValue

  protected def createArchivedProcess(processName: ProcessName, isFragment: Boolean = false): ProcessId = {
    (for {
      id <- prepareValidProcess(processName, Category1, isFragment)
      _ <- dbioRunner.runInTransaction(
        DBIOAction.seq(
          writeProcessRepository.archive(processId = id, isArchived = true),
          actionRepository.markProcessAsArchived(processId = id, VersionId(1))
        )
      )
    } yield id).futureValue
  }

  protected def createDeployedProcessFromProcess(process: CanonicalProcess, category: String = Category1): ProcessId = {
    (for {
      id <- Future(createSavedProcess(process, category, processingType = Streaming))
      _  <- prepareDeploy(id)
    } yield id).futureValue
  }

  protected def parseResponseToListJsonProcess(response: String): List[ProcessJson] = {
    parser.decode[List[Json]](response).rightValue.map(j => ProcessJson(j))
  }

  private def decodeJsonProcess(response: String): ProcessJson =
    ProcessJson(parser.decode[Json](response).rightValue)

}

final case class ProcessVersionJson(id: Long)

object ProcessVersionJson extends OptionValues {

  def apply(process: Json): ProcessVersionJson = ProcessVersionJson(
    process.hcursor.downField("processVersionId").as[Long].toOption.value
  )

}

object ProcessJson extends OptionValues {

  def apply(process: Json): ProcessJson = {
    val lastAction = process.hcursor.downField("lastAction").as[Option[Json]].toOption.value
    val state      = process.hcursor.downField("state").as[Option[Json]].toOption.value

    new ProcessJson(
      process.hcursor.downField("name").as[String].toOption.value,
      lastAction.map(_.hcursor.downField("processVersionId").as[Long].toOption.value),
      lastAction.map(_.hcursor.downField("actionType").as[String].toOption.value),
      state.map(StateJson(_)),
      process.hcursor.downField("processCategory").as[String].toOption.value,
      process.hcursor.downField("isArchived").as[Boolean].toOption.value,
      process.hcursor.downField("history").as[Option[List[Json]]].toOption.value.map(_.map(v => ProcessVersionJson(v)))
    )
  }

}

final case class ProcessJson(
    name: String,
    lastActionVersionId: Option[Long],
    lastActionType: Option[String],
    state: Option[StateJson],
    processCategory: String,
    isArchived: Boolean,
    // Process on list doesn't contain history
    history: Option[List[ProcessVersionJson]]
) {

  def isDeployed: Boolean = lastActionType.contains(ProcessActionType.Deploy.toString)

  def isCanceled: Boolean = lastActionType.contains(ProcessActionType.Cancel.toString)
}

final case class StateJson(name: String, icon: URI, tooltip: String, description: String)

object StateJson extends OptionValues {

  def apply(json: Json): StateJson = new StateJson(
    json.hcursor.downField("status").downField("name").as[String].toOption.value,
    json.hcursor.downField("icon").as[String].toOption.map(URI.create) value,
    json.hcursor.downField("tooltip").as[String].toOption.value,
    json.hcursor.downField("description").as[String].toOption.value,
  )

}

object CreateProcessResponse extends OptionValues {

  def apply(data: Json): CreateProcessResponse = CreateProcessResponse(
    data.hcursor.downField("id").as[Long].map(ProcessId(_)).toOption.value,
    data.hcursor.downField("versionId").as[Long].map(VersionId(_)).toOption.value,
    data.hcursor.downField("processName").as[String].map(ProcessName(_)).toOption.value
  )

}

final case class CreateProcessResponse(id: ProcessId, versionId: VersionId, processName: ProcessName)

object ProcessesQueryEnrichments {

  implicit class RichProcessesQuery(query: ScenarioQuery) {

    def process(): ScenarioQuery =
      query.copy(isFragment = Some(false))

    def fragment(): ScenarioQuery =
      query.copy(isFragment = Some(true))

    def unarchived(): ScenarioQuery =
      query.copy(isArchived = Some(false))

    def archived(): ScenarioQuery =
      query.copy(isArchived = Some(true))

    def deployed(): ScenarioQuery =
      query.copy(isDeployed = Some(true))

    def notDeployed(): ScenarioQuery =
      query.copy(isDeployed = Some(false))

    def names(names: List[String]): ScenarioQuery =
      query.copy(names = Some(names.map(ProcessName(_))))

    def categories(categories: List[String]): ScenarioQuery =
      query.copy(categories = Some(categories))

    def processingTypes(processingTypes: List[String]): ScenarioQuery =
      query.copy(processingTypes = Some(processingTypes))

    def createQueryParamsUrl(path: String): String = {
      var url = s"$path?fake=true"

      query.isArchived.foreach { isArchived =>
        url += s"&isArchived=$isArchived"
      }

      query.isFragment.foreach { isFragment =>
        url += s"&isFragment=$isFragment"
      }

      query.isDeployed.foreach { isDeployed =>
        url += s"&isDeployed=$isDeployed"
      }

      query.categories.foreach { categories =>
        url += s"&categories=${categories.mkString(",")}"
      }

      query.processingTypes.foreach { processingTypes =>
        url += s"&processingTypes=${processingTypes.mkString(",")}"
      }

      query.names.foreach { names =>
        url += s"&names=${names.mkString(",")}"
      }

      url
    }

  }

}
