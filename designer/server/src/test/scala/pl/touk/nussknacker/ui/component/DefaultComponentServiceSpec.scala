package pl.touk.nussknacker.ui.component

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.component.ComponentType._
import pl.touk.nussknacker.engine.api.component.{BuiltInComponentInfo, ComponentGroupName, ComponentId, ComponentInfo}
import pl.touk.nussknacker.engine.api.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.engine.api.process.{ProcessObjectDependencies, ProcessingType}
import pl.touk.nussknacker.engine.testing.LocalModelData
import pl.touk.nussknacker.engine.util.Implicits.RichScalaMap
import pl.touk.nussknacker.engine.{CategoryConfig, ProcessingTypeData}
import pl.touk.nussknacker.restmodel.component.NodeUsageData.{FragmentUsageData, ScenarioUsageData}
import pl.touk.nussknacker.restmodel.component.{ComponentLink, ComponentListElement, NodeUsageData}
import pl.touk.nussknacker.security.Permission
import pl.touk.nussknacker.test.{EitherValuesDetailedMessage, PatientScalaFutures}
import pl.touk.nussknacker.ui.api.helpers.TestProcessUtil._
import pl.touk.nussknacker.ui.api.helpers.TestProcessingTypes._
import pl.touk.nussknacker.ui.api.helpers.{
  MockDeploymentManager,
  MockFetchingProcessRepository,
  MockManagerProvider,
  TestFactory
}
import pl.touk.nussknacker.ui.component.ComponentModelData._
import pl.touk.nussknacker.ui.component.ComponentTestProcessData._
import pl.touk.nussknacker.ui.component.DefaultsComponentGroupName._
import pl.touk.nussknacker.ui.component.DefaultsComponentIcon._
import pl.touk.nussknacker.ui.component.DynamicComponentProvider._
import pl.touk.nussknacker.ui.config.ComponentLinkConfig._
import pl.touk.nussknacker.ui.config.{ComponentLinkConfig, ComponentLinksConfigExtractor}
import pl.touk.nussknacker.ui.definition.TestAdditionalUIConfigProvider
import pl.touk.nussknacker.ui.process.ProcessCategoryService.Category
import pl.touk.nussknacker.ui.process.fragment.DefaultFragmentRepository
import pl.touk.nussknacker.ui.process.processingtypedata.{ProcessingTypeDataProvider, ProcessingTypeDataReader}
import pl.touk.nussknacker.ui.process.repository.ScenarioWithDetailsEntity
import pl.touk.nussknacker.ui.process.{ConfigProcessCategoryService, DBProcessService, ProcessCategoryService}
import pl.touk.nussknacker.ui.security.api.LoggedUser

import java.net.URI

class DefaultComponentServiceSpec
    extends AnyFlatSpec
    with Matchers
    with PatientScalaFutures
    with EitherValuesDetailedMessage
    with OptionValues {

  import org.scalatest.prop.TableDrivenPropertyChecks._

  import scala.concurrent.ExecutionContext.Implicits.global

  private val executionGroupName: ComponentGroupName  = ComponentGroupName("execution")
  private val responseGroupName: ComponentGroupName   = ComponentGroupName("response")
  private val hiddenGroupName: ComponentGroupName     = ComponentGroupName("hidden")
  private val overriddenGroupName: ComponentGroupName = ComponentGroupName("OverriddenGroupName")
  private val overriddenIcon                          = "OverriddenIcon.svg"
  private val filterDocsUrl = "https://nussknacker.io/documentation/docs/scenarios_authoring/BasicNodes#filter"

  private val usagesLinkId = "usages"
  private val invokeLinkId = "invoke"
  private val editLinkId   = "edit"
  private val filterLinkId = "filter"

  private val linkConfigs = List(
    createLinkConfig(
      usagesLinkId,
      s"Usages of $ComponentNameTemplate",
      s"/assets/components/links/usages.svg",
      s"https://list-of-usages.com/$ComponentIdTemplate/",
      None
    ),
    createLinkConfig(
      invokeLinkId,
      s"Invoke component $ComponentNameTemplate",
      s"/assets/components/links/invoke.svg",
      s"https://components.com/$ComponentIdTemplate/Invoke",
      Some(List(Service))
    ),
    createLinkConfig(
      editLinkId,
      s"Edit component $ComponentNameTemplate",
      "/assets/components/links/edit.svg",
      s"https://components.com/$ComponentIdTemplate/",
      Some(List(CustomComponent, Service))
    ),
    createLinkConfig(
      filterLinkId,
      s"Custom link $ComponentNameTemplate",
      "https://other-domain.com/assets/components/links/filter.svg",
      s"https://components.com/$ComponentIdTemplate/filter",
      Some(List(BuiltIn))
    ),
  )

  private val filterDocsLink = ComponentLink.createDocumentationLink(filterDocsUrl)

  private val componentLinksConfig = ComponentLinksConfigExtractor.extract(
    ConfigFactory.parseString(
      s"""
      componentLinks: [
        ${linkConfigs
          .map { link =>
            s"""{
           | id: "${link.id}",
           | title: "${link.title}",
           | url: "${link.url}",
           | icon: "${link.icon}",
           | ${link.supportedComponentTypes
                .map(types => s"""supportedComponentTypes: [${types.mkString(",")}]""")
                .getOrElse("")}
           | }""".stripMargin
          }
          .mkString(",\n")}
      ]
    """
    )
  )

  private val overrideKafkaSinkComponentId    = ComponentId(s"$Sink-$KafkaAvroProvidedComponentName")
  private val overrideKafkaSourceComponentId  = ComponentId(s"$Source-$KafkaAvroProvidedComponentName")
  private val customerDataEnricherComponentId = ComponentId(CustomerDataEnricherName)
  private val sharedEnricherComponentId       = ComponentId(SharedEnricherName)
  private val customStreamComponentId         = ComponentId(CustomStreamName)
  private val sharedSourceComponentId         = ComponentId(SharedSourceName)
  private val sharedProvidedComponentId       = ComponentId(SharedProvidedComponentName)

  private val streamingConfig: Config = ConfigFactory.parseString(s"""
       |{
       |  componentsUiConfig {
       |    $CustomerDataEnricherName {
       |      icon: "$overriddenIcon"
       |      componentGroup: "$responseGroupName"
       |      componentId: "$customerDataEnricherComponentId"
       |    },
       |    ${BuiltInComponentInfo.Filter.name} {
       |      icon: "$overriddenIcon"
       |      docsUrl: "$filterDocsUrl"
       |    },
       |    $HiddenMarketingCustomerDataEnricherName {
       |     componentGroup: "$hiddenGroupName"
       |    },
       |    $SharedEnricherName {
       |      icon: "$overriddenIcon"
       |    },
       |    $SharedProvidedComponentName {
       |      componentId: $SharedProvidedComponentName
       |    },
       |    ${cid(Streaming, ComponentInfo(Source, KafkaAvroProvidedComponentName))} {
       |      componentId: "$overrideKafkaSourceComponentId"
       |    }
       |    ${cid(Streaming, ComponentInfo(Sink, KafkaAvroProvidedComponentName))} {
       |      componentId: "$overrideKafkaSinkComponentId"
       |    }
       |  }
       |
       |  componentsGroupMapping {
       |    "$SinksGroupName": "$executionGroupName",
       |    "$ServicesGroupName": "$executionGroupName",
       |    "$hiddenGroupName": null
       |  }
       |}
       |""".stripMargin)

  private val fraudConfig: Config = ConfigFactory.parseString(s"""
       |{
       |  componentsUiConfig {
       |    $HiddenFraudCustomerDataEnricherName {
       |     componentGroup: "$hiddenGroupName"
       |    }
       |    $CategoryFraud {
       |      icon: "$overriddenIcon"
       |    }
       |    ${BuiltInComponentInfo.Filter.name} {
       |      icon: "$overriddenIcon"
       |      docsUrl: "$filterDocsUrl"
       |    },
       |    $SharedEnricherName {
       |      icon: "$overriddenIcon"
       |    },
       |    $SharedProvidedComponentName {
       |      componentId: $SharedProvidedComponentName
       |    },
       |    ${cid(Fraud, ComponentInfo(Source, KafkaAvroProvidedComponentName))} {
       |      componentId: "$overrideKafkaSourceComponentId"
       |    }
       |    ${cid(Fraud, ComponentInfo(Sink, KafkaAvroProvidedComponentName))} {
       |      componentId: "$overrideKafkaSinkComponentId"
       |    }
       |  }
       |
       |  componentsGroupMapping {
       |    "$SinksGroupName": "$executionGroupName",
       |    "$ServicesGroupName": "$executionGroupName",
       |    "$hiddenGroupName": null
       |  }
       |}
       |""".stripMargin)

  private val wrongConfig: Config = ConfigFactory.parseString(s"""
       |{
       |  componentsUiConfig {
       |    $SharedSourceV2Name {
       |      icon: "$overriddenIcon"
       |      componentGroup: "$executionGroupName"
       |    },
       |    $SharedProvidedComponentName {
       |      componentId: "$SharedProvidedComponentName"
       |      icon: "$overriddenIcon"
       |      componentGroup: $overriddenGroupName
       |    }
       |  }
       |}
       |""".stripMargin)

  private val categoryService = ConfigProcessCategoryService(
    ConfigFactory.empty,
    Map(
      Streaming -> CategoryConfig(CategoryMarketing),
      Fraud     -> CategoryConfig(CategoryFraud)
    )
  )

  private val baseComponents: List[ComponentListElement] =
    List(
      baseComponent(BuiltInComponentInfo.Filter, overriddenIcon, BaseGroupName, AllCategories),
      baseComponent(BuiltInComponentInfo.Split, SplitIcon, BaseGroupName, AllCategories),
      baseComponent(BuiltInComponentInfo.Choice, ChoiceIcon, BaseGroupName, AllCategories),
      baseComponent(BuiltInComponentInfo.Variable, VariableIcon, BaseGroupName, AllCategories),
      baseComponent(BuiltInComponentInfo.RecordVariable, RecordVariableIcon, BaseGroupName, AllCategories),
    )

  private def prepareSharedComponents(implicit user: LoggedUser): List[ComponentListElement] =
    List(
      sharedComponent(
        ComponentInfo(Source, SharedSourceName),
        SourceIcon,
        SourcesGroupName,
      ),
      sharedComponent(
        ComponentInfo(Sink, SharedSinkName),
        SinkIcon,
        executionGroupName,
      ),
      sharedComponent(
        ComponentInfo(Service, SharedEnricherName),
        overriddenIcon,
        EnrichersGroupName,
      ),
      sharedComponent(
        ComponentInfo(Service, SharedProvidedComponentName),
        ProcessorIcon,
        executionGroupName,
      ),
      sharedComponent(
        ComponentInfo(Source, KafkaAvroProvidedComponentName),
        SourceIcon,
        SourcesGroupName,
        componentId = Some(overrideKafkaSourceComponentId)
      ),
      sharedComponent(
        ComponentInfo(Sink, KafkaAvroProvidedComponentName),
        SinkIcon,
        executionGroupName,
        componentId = Some(overrideKafkaSinkComponentId)
      ),
    )

  private def prepareMarketingComponents(implicit user: LoggedUser): List[ComponentListElement] = List(
    marketingComponent(
      ComponentInfo(CustomComponent, CustomStreamName),
      CustomComponentIcon,
      CustomGroupName,
      componentId = Some(customStreamComponentId)
    ),
    marketingComponent(
      ComponentInfo(Service, CustomerDataEnricherName),
      overriddenIcon,
      responseGroupName,
      componentId = Some(customerDataEnricherComponentId)
    ),
    marketingComponent(
      ComponentInfo(Service, FuseBlockServiceName),
      ProcessorIcon,
      executionGroupName
    ),
    marketingComponent(ComponentInfo(Sink, MonitorName), SinkIcon, executionGroupName),
    marketingComponent(
      ComponentInfo(CustomComponent, OptionalCustomStreamName),
      CustomComponentIcon,
      OptionalEndingCustomGroupName
    ),
    marketingComponent(ComponentInfo(Source, SuperMarketingSourceName), SourceIcon, SourcesGroupName),
    marketingComponent(ComponentInfo(Source, NotSharedSourceName), SourceIcon, SourcesGroupName),
    marketingComponent(
      ComponentInfo(Service, SingleProvidedComponentName),
      ProcessorIcon,
      executionGroupName
    ),
  )

  private def prepareFraudComponents(implicit user: LoggedUser): List[ComponentListElement] = List(
    fraudComponent(ComponentInfo(CustomComponent, CustomStreamName), CustomComponentIcon, CustomGroupName),
    fraudComponent(ComponentInfo(Service, CustomerDataEnricherName), EnricherIcon, EnrichersGroupName),
    fraudComponent(ComponentInfo(Service, FuseBlockServiceName), ProcessorIcon, executionGroupName),
    fraudComponent(
      ComponentInfo(CustomComponent, OptionalCustomStreamName),
      CustomComponentIcon,
      OptionalEndingCustomGroupName
    ),
    fraudComponent(ComponentInfo(Sink, SecondMonitorName), SinkIcon, executionGroupName),
    fraudComponent(ComponentInfo(Service, SingleProvidedComponentName), ProcessorIcon, executionGroupName),
    fraudComponent(ComponentInfo(Source, NotSharedSourceName), SourceIcon, SourcesGroupName),
    fraudComponent(ComponentInfo(Sink, FraudSinkName), SinkIcon, executionGroupName),
  )

  private def sharedComponent(
      componentInfo: ComponentInfo,
      icon: String,
      componentGroupName: ComponentGroupName,
      componentId: Option[ComponentId] = None
  )(implicit user: LoggedUser) = {
    val id         = componentId.getOrElse(ComponentId(componentInfo.name))
    val links      = createLinks(id, componentInfo)
    val usageCount = componentCount(id, user)

    val availableCategories = AllCategories.filter(user.can(_, Permission.Read)).sorted

    ComponentListElement(
      id,
      componentInfo.name,
      icon,
      componentInfo.`type`,
      componentGroupName,
      availableCategories,
      links,
      usageCount
    )
  }

  private val fragmentMarketingComponents: List[ComponentListElement] = {
    val cat           = CategoryMarketing
    val componentInfo = ComponentInfo(Fragment, cat)
    val componentId   = cid(Streaming, componentInfo)
    val icon          = DefaultsComponentIcon.fromComponentInfo(componentInfo, None)
    val links         = createLinks(componentId, componentInfo)
    List(ComponentListElement(componentId, cat, icon, Fragment, FragmentsGroupName, List(cat), links, 0))
  }

  private val fragmentFraudComponents: List[ComponentListElement] = {
    val cat           = CategoryFraud
    val componentInfo = ComponentInfo(Fragment, cat)
    val componentId   = cid(Fraud, componentInfo)
    val icon =
      if (cat == CategoryFraud) overriddenIcon else DefaultsComponentIcon.fromComponentInfo(componentInfo, None)
    val links = createLinks(componentId, componentInfo)
    List(ComponentListElement(componentId, cat, icon, Fragment, FragmentsGroupName, List(cat), links, 0))
  }

  private def prepareComponents(implicit user: LoggedUser): List[ComponentListElement] =
    baseComponents ++ prepareSharedComponents ++ prepareMarketingComponents ++ prepareFraudComponents ++ fragmentMarketingComponents ++ fragmentFraudComponents

  private val fragmentFromCategories = AllCategories
    .flatMap(cat =>
      categoryService
        .getTypeForCategory(cat)
        .map(processingType => createFragment(cat, category = cat, processingType = processingType))
    )
    .toSet

  private def marketingComponent(
      componentInfo: ComponentInfo,
      icon: String,
      componentGroupName: ComponentGroupName,
      componentId: Option[ComponentId] = None
  )(implicit user: LoggedUser) =
    createComponent(Streaming, componentInfo, icon, componentGroupName, List(CategoryMarketing), componentId)

  private def fraudComponent(
      componentInfo: ComponentInfo,
      icon: String,
      componentGroupName: ComponentGroupName,
      componentId: Option[ComponentId] = None
  )(implicit user: LoggedUser) =
    createComponent(Fraud, componentInfo, icon, componentGroupName, List(CategoryFraud), componentId)

  private def createComponent(
      processingType: String,
      componentInfo: ComponentInfo,
      icon: String,
      componentGroupName: ComponentGroupName,
      categories: List[String],
      componentId: Option[ComponentId] = None
  )(implicit user: LoggedUser) = {
    val compId     = componentId.getOrElse(cid(processingType, componentInfo))
    val links      = createLinks(compId, componentInfo)
    val usageCount = componentCount(compId, user)
    ComponentListElement(
      compId,
      componentInfo.name,
      icon,
      componentInfo.`type`,
      componentGroupName,
      categories,
      links,
      usageCount
    )
  }

  private def baseComponent(
      componentInfo: ComponentInfo,
      icon: String,
      componentGroupName: ComponentGroupName,
      categories: List[String]
  ): ComponentListElement = {
    val componentId = bid(componentInfo)
    val docsLinks   = if (componentInfo.name == BuiltInComponentInfo.Filter.name) List(filterDocsLink) else Nil
    val links       = docsLinks ++ createLinks(componentId, componentInfo)
    ComponentListElement(
      componentId,
      componentInfo.name,
      icon,
      componentInfo.`type`,
      componentGroupName,
      categories,
      links,
      0
    )
  }

  private def createLinks(
      componentId: ComponentId,
      componentInfo: ComponentInfo
  ): List[ComponentLink] =
    linkConfigs
      .filter(_.isAvailable(componentInfo.`type`))
      .map(_.toComponentLink(componentId, componentInfo.name))

  private def componentCount(componentId: ComponentId, user: LoggedUser) = {
    val sourceComponentId = ComponentId(SharedSourceName)
    val sinkComponentId   = ComponentId(SharedSinkName)

    componentId match {
      // Order is matter, first should be condition with more number of categories
      case _ @id if id == sourceComponentId && hasAccess(user, CategoryFraud, CategoryMarketing) => 2
      case _ @id if id == sinkComponentId && hasAccess(user, CategoryFraud, CategoryMarketing)   => 2

      case _ @id if id == sourceComponentId && hasAccess(user, CategoryFraud) => 1
      case _ @id if id == sinkComponentId && hasAccess(user, CategoryFraud)   => 1

      case _ @id if id == sourceComponentId && hasAccess(user, CategoryMarketing) => 1
      case _ @id if id == sinkComponentId && hasAccess(user, CategoryMarketing)   => 1

      case _ => 0
    }
  }

  private def hasAccess(user: LoggedUser, categories: Category*): Boolean =
    categories.forall(cat => user.can(cat, Permission.Read))

  private val admin = TestFactory.adminUser()

  private val marketingUser = TestFactory.userWithCategoriesReadPermission(
    username = "marketingUser",
    categories = List(CategoryMarketing)
  )

  private val fraudUser =
    TestFactory.userWithCategoriesReadPermission(username = "fraudUser", categories = List(CategoryFraud))

  private val providerComponents =
    new DynamicComponentProvider().create(ConfigFactory.empty, ProcessObjectDependencies.empty)

  private val processingTypeDataMap: Map[Category, ProcessingTypeData] = Map(
    Streaming -> (LocalModelData(
      streamingConfig,
      providerComponents,
      ComponentMarketingTestConfigCreator
    ), CategoryMarketing),
    Fraud -> (LocalModelData(fraudConfig, providerComponents, ComponentFraudTestConfigCreator), CategoryFraud)
  ).transform { case (_, (modelData, category)) =>
    ProcessingTypeData.createProcessingTypeData(
      MockManagerProvider,
      new MockDeploymentManager,
      modelData,
      ConfigFactory.empty(),
      CategoryConfig(category)
    )
  }

  private val processingTypeDataProvider = ProcessingTypeDataProvider(
    processingTypeDataMap.mapValuesNow(ProcessingTypeDataReader.toValueWithPermission),
    (ComponentIdProviderFactory.createUnsafe(processingTypeDataMap, categoryService), categoryService)
  )

  it should "return components for each user" in {
    val processes      = List(MarketingProcess, FraudProcess, ArchivedFraudProcess)
    val processService = createDbProcessService(categoryService, processes)
    val defaultComponentService =
      new DefaultComponentService(
        componentLinksConfig,
        processingTypeDataProvider,
        processService,
        createFragmentRepository(fragmentFromCategories.toList),
        TestAdditionalUIConfigProvider
      )

    def filterUserComponents(user: LoggedUser, categories: List[String]): List[ComponentListElement] =
      prepareComponents(user)
        .map(c => c -> categories.intersect(c.categories))
        .filter(seq => seq._2.nonEmpty)
        .map(seq => seq._1.copy(categories = seq._2))

    val adminComponents     = prepareComponents(admin)
    val marketingComponents = filterUserComponents(marketingUser, List(CategoryMarketing))
    val fraudComponents     = filterUserComponents(fraudUser, List(CategoryFraud))

    val testingData = Table(
      ("user", "expectedComponents", "possibleCategories"),
      (marketingUser, marketingComponents, List(CategoryMarketing)),
      (fraudUser, fraudComponents, List(CategoryFraud)),
      (admin, adminComponents, AllCategories)
    )

    forAll(testingData) {
      (user: LoggedUser, expectedComponents: List[ComponentListElement], possibleCategories: List[String]) =>
        val components = defaultComponentService.getComponentsList(user).futureValue

        def counts(list: List[ComponentListElement]) = list.map(el => el.id -> el.usageCount).toMap

        val returnedCounts = counts(components)
        val expectedCounts = counts(expectedComponents)
        returnedCounts.keys.toList.sortBy(_.value) should contain theSameElementsAs expectedCounts.keys.toList.sortBy(
          _.value
        )
        returnedCounts should contain theSameElementsAs expectedCounts
        components should contain theSameElementsAs expectedComponents

        // Components should contain only user categories
        val componentsCategories = components.flatMap(_.categories).distinct.sorted
        componentsCategories.diff(possibleCategories).isEmpty shouldBe true

        forAll(Table("component", components: _*)) { comp =>
          // See linksConfig
          val availableLinksId = comp.componentInfo match {
            case ComponentInfo(Service, _)         => List(usagesLinkId, invokeLinkId, editLinkId)
            case ComponentInfo(CustomComponent, _) => List(usagesLinkId, editLinkId)
            case ComponentInfo(BuiltIn, _)         => List(usagesLinkId, filterLinkId)
            case _                                 => List(usagesLinkId)
          }

          val availableDocsLinksId = comp.componentInfo match {
            case BuiltInComponentInfo.Filter => List(filterDocsLink.id)
            case _                           => Nil
          }

          // Base components from providers contain more links because of documentation
          comp.links.map(_.id) should contain theSameElementsAs availableDocsLinksId ++ availableLinksId

          comp.links
            .filter(l => availableLinksId.contains(l.id))
            .foreach(link => {
              link.title should include(comp.name)
              link.url.toString should include(comp.id.value)
            })
        }
    }
  }

  it should "throws exception when components are wrong configured" in {
    import WrongConfigurationAttribute._
    val badProcessingTypeDataMap = Map(
      Streaming -> (LocalModelData(
        streamingConfig,
        providerComponents,
        ComponentMarketingTestConfigCreator
      ), CategoryMarketing),
      Fraud -> (LocalModelData(wrongConfig, providerComponents, WronglyConfiguredConfigCreator), CategoryFraud)
    ).map { case (processingType, (modelData, category)) =>
      processingType -> ProcessingTypeData.createProcessingTypeData(
        MockManagerProvider,
        new MockDeploymentManager,
        modelData,
        ConfigFactory.empty(),
        CategoryConfig(category)
      )
    }
    val componentObjectsService = new ComponentObjectsService(categoryService)
    val componentObjectsMap =
      badProcessingTypeDataMap.transform(componentObjectsService.prepareWithoutFragmentsAndAdditionalUIConfigs)
    val componentIdProvider = new DefaultComponentIdProvider(componentObjectsMap.transform {
      case (_, componentsObjects) => componentsObjects.config
    })

    val expectedWrongConfigurations = List(
      ComponentWrongConfiguration(
        bid(BuiltInComponentInfo.Filter),
        IconAttribute,
        List(overriddenIcon, DefaultsComponentIcon.forBuiltInComponent(BuiltInComponentInfo.Filter))
      ),
      ComponentWrongConfiguration(sharedSourceComponentId, NameAttribute, List(SharedSourceName, SharedSourceV2Name)),
      ComponentWrongConfiguration(
        sharedSourceComponentId,
        IconAttribute,
        List(DefaultsComponentIcon.forNotBuiltInComponentType((Source, None)), overriddenIcon)
      ),
      ComponentWrongConfiguration(
        sharedSourceComponentId,
        ComponentGroupNameAttribute,
        List(SourcesGroupName, executionGroupName)
      ),
      ComponentWrongConfiguration(
        sharedEnricherComponentId,
        IconAttribute,
        List(overriddenIcon, DefaultsComponentIcon.forNotBuiltInComponentType((Service, Some(false))))
      ),
      ComponentWrongConfiguration(
        sharedEnricherComponentId,
        ComponentGroupNameAttribute,
        List(EnrichersGroupName, ServicesGroupName)
      ),
      ComponentWrongConfiguration(
        sharedProvidedComponentId,
        IconAttribute,
        List(DefaultsComponentIcon.forNotBuiltInComponentType((Service, Some(false))), overriddenIcon)
      ),
      ComponentWrongConfiguration(
        sharedProvidedComponentId,
        ComponentGroupNameAttribute,
        List(executionGroupName, overriddenGroupName)
      )
    )

    val wrongConfigurations = intercept[ComponentConfigurationException] {
      ComponentsValidator.checkUnsafe(componentObjectsMap, componentIdProvider)
    }.wrongConfigurations

    wrongConfigurations.toList should contain theSameElementsAs expectedWrongConfigurations
  }

  it should "return components usage" in {
    val processes = List(
      MarketingProcess,
      FraudProcess,
      FraudProcessWithNotSharedSource,
      CanceledFraudProcessWith2Enrichers,
      DeployedFraudProcessWith2Filters,
      ArchivedFraudProcess,
      FraudProcessWithFragment,
      FraudFragment
    )

    val fraudNotSharedSourceComponentId      = cid(Fraud, ComponentInfo(Source, NotSharedSourceName))
    val fraudCustomerDataEnricherComponentId = cid(Fraud, ComponentInfo(Service, CustomerDataEnricherName))
    val sharedSourceComponentId              = ComponentId(SharedSourceName) // it's shared id - merged at configs file
    val fragmentComponentId                  = cid(Fraud, ComponentInfo(Fragment, FraudFragmentName))
    val filterComponentId                    = bid(BuiltInComponentInfo.Filter)

    val processService = createDbProcessService(categoryService, processes)
    val defaultComponentService =
      new DefaultComponentService(
        componentLinksConfig,
        processingTypeDataProvider,
        processService,
        createFragmentRepository(List(FraudFragment)),
        TestAdditionalUIConfigProvider
      )

    val testingData = Table(
      ("user", "componentId", "expected"),
      (admin, fragmentComponentId, List((FraudProcessWithFragment, List(ScenarioUsageData(FraudFragmentName))))),
      (
        admin,
        sharedSourceComponentId,
        List(
          (CanceledFraudProcessWith2Enrichers, List(ScenarioUsageData(DefaultSourceName))),
          (DeployedFraudProcessWith2Filters, List(ScenarioUsageData(DefaultSourceName))),
          (FraudProcess, List(ScenarioUsageData(DefaultSourceName))),
          (FraudProcessWithFragment, List(ScenarioUsageData(SecondSourceName))),
          (MarketingProcess, List(ScenarioUsageData(DefaultSourceName))),
        )
      ),
      (
        admin,
        fraudNotSharedSourceComponentId,
        List((FraudProcessWithNotSharedSource, List(ScenarioUsageData(DefaultSourceName))))
      ),
      (
        admin,
        fraudCustomerDataEnricherComponentId,
        List(
          (
            CanceledFraudProcessWith2Enrichers,
            List(ScenarioUsageData(DefaultCustomName), ScenarioUsageData(SecondCustomName))
          )
        )
      ),
      (
        admin,
        filterComponentId,
        List(
          (
            DeployedFraudProcessWith2Filters,
            List(ScenarioUsageData(DefaultFilterName), ScenarioUsageData(SecondFilterName))
          ),
          (FraudFragment, List(ScenarioUsageData(FragmentFilterName))),
          (
            FraudProcessWithFragment,
            List(ScenarioUsageData(SecondFilterName), FragmentUsageData(FraudFragment.name.value, FragmentFilterName))
          ),
        )
      ),
    )

    forAll(testingData) {
      (
          user: LoggedUser,
          componentId: ComponentId,
          expected: List[(ScenarioWithDetailsEntity[_], List[NodeUsageData])]
      ) =>
        val result = defaultComponentService
          .getComponentUsages(componentId)(user)
          .futureValue
          .map(_.map(n => n.copy(nodesUsagesData = n.nodesUsagesData.sorted)))
        val componentProcesses = expected.map { case (process, nodesUsagesData) =>
          DefaultComponentService.toComponentUsagesInScenario(process, nodesUsagesData.sorted)
        }
        result shouldBe Right(componentProcesses)
    }
  }

  it should "return return error when component doesn't exist" in {
    val processService = createDbProcessService(categoryService)
    val defaultComponentService =
      new DefaultComponentService(
        componentLinksConfig,
        processingTypeDataProvider,
        processService,
        createFragmentRepository(List.empty),
        TestAdditionalUIConfigProvider
      )
    val notExistComponentId = ComponentId("not-exist")
    val result              = defaultComponentService.getComponentUsages(notExistComponentId)(admin).futureValue
    result shouldBe Left(ComponentNotFoundError(notExistComponentId))
  }

  private def createDbProcessService(
      processCategoryService: ProcessCategoryService,
      processes: List[ScenarioWithDetailsEntity[DisplayableProcess]] = Nil
  ): DBProcessService =
    new DBProcessService(
      deploymentService = TestFactory.deploymentService(),
      newProcessPreparer = TestFactory.createNewProcessPreparer(),
      getProcessCategoryService = () => processCategoryService,
      processResolverByProcessingType = TestFactory.processResolverByProcessingType,
      dbioRunner = TestFactory.newDummyDBIOActionRunner(),
      fetchingProcessRepository = MockFetchingProcessRepository.withProcessesDetails(processes),
      processActionRepository = TestFactory.newDummyActionRepository(),
      processRepository = TestFactory.newDummyWriteProcessRepository()
    )

  private def createFragmentRepository(fragments: List[ScenarioWithDetailsEntity[DisplayableProcess]]) =
    new DefaultFragmentRepository(MockFetchingProcessRepository.withProcessesDetails(fragments))

  private def cid(processingType: ProcessingType, componentInfo: ComponentInfo): ComponentId =
    ComponentId.default(processingType, componentInfo)

  private def bid(componentInfo: ComponentInfo): ComponentId =
    ComponentId.forBuiltInComponent(componentInfo)

  private implicit def ordering: Ordering[NodeUsageData] = (x: NodeUsageData, y: NodeUsageData) => {
    x.nodeId.compareTo(y.nodeId)
  }

  private def createLinkConfig(
      id: String,
      title: String,
      icon: String,
      url: String,
      supportedComponentTypes: Option[List[ComponentType]]
  ): ComponentLinkConfig =
    ComponentLinkConfig(id, title, URI.create(icon), URI.create(url), supportedComponentTypes)

}
