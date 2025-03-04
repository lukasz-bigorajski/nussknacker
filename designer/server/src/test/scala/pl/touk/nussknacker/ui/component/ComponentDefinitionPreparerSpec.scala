package pl.touk.nussknacker.ui.component

import org.scalatest.Inside.inside
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.component._
import pl.touk.nussknacker.engine.api.definition.Parameter
import pl.touk.nussknacker.engine.api.typed.typing.Unknown
import pl.touk.nussknacker.engine.definition.component.{ComponentStaticDefinition, CustomComponentSpecificData}
import pl.touk.nussknacker.engine.definition.fragment.FragmentStaticDefinition
import pl.touk.nussknacker.engine.definition.model.ModelDefinition
import pl.touk.nussknacker.engine.graph.EdgeType._
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.graph.node.WithParameters
import pl.touk.nussknacker.engine.modelconfig.ComponentsUiConfig
import pl.touk.nussknacker.engine.testing.ModelDefinitionBuilder
import pl.touk.nussknacker.engine.testing.ModelDefinitionBuilder.ComponentDefinitionBuilder
import pl.touk.nussknacker.engine.util.Implicits.RichScalaMap
import pl.touk.nussknacker.restmodel.definition.{ComponentGroup, NodeEdges}
import pl.touk.nussknacker.ui.api.helpers.{ProcessTestData, TestFactory, TestPermissions, TestProcessingTypes}
import pl.touk.nussknacker.ui.util.ConfigWithScalaVersion

class ComponentDefinitionPreparerSpec extends AnyFunSuite with Matchers with TestPermissions with OptionValues {

  private val processCategoryService = TestFactory.createCategoryService(ConfigWithScalaVersion.TestsConfig)

  test("return groups sorted in order: inputs, base, other, outputs and then sorted by name within group") {
    val groups = prepareGroups(
      Map(),
      Map(
        ComponentGroupName("custom") -> Some(ComponentGroupName("CUSTOM")),
        ComponentGroupName("sinks")  -> Some(ComponentGroupName("BAR"))
      )
    )
    groups
      .map(_.name) shouldBe List("sources", "base", "CUSTOM", "enrichers", "BAR", "optionalEndingCustom", "services")
      .map(ComponentGroupName(_))
  }

  test("return groups with hidden base group") {
    val groups = prepareGroups(Map.empty, Map(ComponentGroupName("base") -> None))
    groups.map(_.name) shouldBe List("sources", "custom", "enrichers", "optionalEndingCustom", "services", "sinks").map(
      ComponentGroupName(_)
    )
  }

  test("return objects sorted by label case insensitive") {
    val groups = prepareGroupsOfNodes(List("foo", "alaMaKota", "BarFilter"))
    groups.map(_.components.map(n => n.label)) shouldBe List(
      List("choice", "filter", "record-variable", "split", "variable"),
      List("alaMaKota", "BarFilter", "foo")
    )
  }

  test("return edge types for fragment, filters, switches and components with multiple inputs") {
    val fragmentsDetails = TestFactory.prepareSampleFragmentRepository.fragmentsByProcessingType.head._2

    val edgeTypes = ComponentDefinitionPreparer.prepareEdgeTypes(
      modelDefinition = ProcessTestData.modelDefinition,
      isFragment = false,
      fragmentsDetails = fragmentsDetails
    )

    edgeTypes.toSet shouldBe Set(
      NodeEdges(
        BuiltInComponentInfo.Split,
        List.empty,
        canChooseNodes = true,
        isForInputDefinition = false
      ),
      NodeEdges(
        BuiltInComponentInfo.Choice,
        List(NextSwitch(Expression.spel("true")), SwitchDefault),
        canChooseNodes = true,
        isForInputDefinition = false
      ),
      NodeEdges(
        BuiltInComponentInfo.Filter,
        List(FilterTrue, FilterFalse),
        canChooseNodes = false,
        isForInputDefinition = false
      ),
      NodeEdges(
        ComponentInfo(ComponentType.Fragment, "sub1"),
        List(FragmentOutput("out1"), FragmentOutput("out2")),
        canChooseNodes = false,
        isForInputDefinition = false
      ),
      NodeEdges(
        ComponentInfo(ComponentType.CustomComponent, "union"),
        List.empty,
        canChooseNodes = true,
        isForInputDefinition = true
      )
    )
  }

  test("return objects with mapped groups") {
    val groups = prepareGroups(
      Map(),
      Map(
        ComponentGroupName("custom")               -> Some(ComponentGroupName("base")),
        ComponentGroupName("optionalEndingCustom") -> Some(ComponentGroupName("base"))
      )
    )

    validateGroups(groups, 5)

    groups.exists(_.name == ComponentGroupName("custom")) shouldBe false

    val baseComponentsGroups = groups.filter(_.name == ComponentGroupName("base"))
    baseComponentsGroups should have size 1

    val baseComponents = baseComponentsGroups.flatMap(_.components)
    baseComponents
      .filter(n => n.`type` == ComponentType.BuiltIn)
      .map(_.label) should contain allElementsOf BuiltInComponentInfo.AllAvailableForScenario.map(_.name)
    baseComponents.filter(n => n.`type` == ComponentType.CustomComponent) should have size 5
  }

  test("return objects with mapped nodes") {
    val groups = prepareGroups(
      Map("barService" -> "foo", "barSource" -> "fooBar"),
      Map.empty
    )

    val fooNodes = groups.filter(_.name == ComponentGroupName("foo")).flatMap(_.components)
    fooNodes should have size 1
    fooNodes.filter(_.label == "barService") should have size 1
  }

  test("return custom nodes with correct group") {
    val definitionWithCustomNodesInSomeCategory = ProcessTestData.modelDefinition.transform {
      case component if component.componentType == ComponentType.CustomComponent =>
        val updatedComponentConfig = component.componentConfig.copy(componentGroup = Some(ComponentGroupName("cat1")))
        component.copy(componentConfig = updatedComponentConfig)
      case other => other
    }
    val groups = prepareGroups(Map.empty, Map.empty, definitionWithCustomNodesInSomeCategory)

    groups.exists(_.name == ComponentGroupName("custom")) shouldBe false
    groups.exists(_.name == ComponentGroupName("cat1")) shouldBe true
  }

  test("return default value defined in parameter") {
    val defaultValueExpression = Expression("fooLang", "'fooDefault'")
    val parameter              = Parameter[String]("fooParameter").copy(defaultValue = Some(defaultValueExpression))
    val definition = ModelDefinitionBuilder.empty
      .withCustomStreamTransformer(
        "fooTransformer",
        Some(Unknown),
        CustomComponentSpecificData(manyInputs = false, canBeEnding = true),
        parameter
      )

    val groups           = prepareGroups(Map.empty, Map.empty, definition)
    val transformerGroup = groups.find(_.name == ComponentGroupName("optionalEndingCustom")).value
    inside(transformerGroup.components.head.node) { case withParameters: WithParameters =>
      withParameters.parameters.head.expression shouldEqual defaultValueExpression
    }
  }

  test("should prefer config over code configuration") {
    val fixed = ComponentsUiConfig(
      Map(
        "service"  -> SingleComponentConfig(None, None, Some("doc"), None, Some(ComponentId("fixed"))),
        "serviceC" -> SingleComponentConfig(None, None, Some("doc"), None, None),
        "serviceA" -> SingleComponentConfig(None, None, Some("doc"), None, None)
      )
    )

    val dynamic = ComponentsUiConfig(
      Map(
        "service"  -> SingleComponentConfig(None, None, Some("doc1"), None, Some(ComponentId("dynamic"))),
        "serviceB" -> SingleComponentConfig(None, None, Some("doc"), None, None),
        "serviceC" -> SingleComponentConfig(None, None, Some("doc"), None, Some(ComponentId("dynamic"))),
      )
    )

    val expected = ComponentsUiConfig(
      Map(
        "service"  -> SingleComponentConfig(None, None, Some("doc"), None, Some(ComponentId("fixed"))),
        "serviceA" -> SingleComponentConfig(None, None, Some("doc"), None, None),
        "serviceB" -> SingleComponentConfig(None, None, Some("doc"), None, None),
        "serviceC" -> SingleComponentConfig(None, None, Some("doc"), None, Some(ComponentId("dynamic"))),
      )
    )

    ComponentDefinitionPreparer.combineComponentsConfig(fixed, dynamic) shouldBe expected
  }

  test("should merge default value maps") {
    val fixed = ComponentsUiConfig(
      Map(
        "service" -> SingleComponentConfig(
          Some(Map("a" -> "x", "b" -> "y").mapValuesNow(dv => ParameterConfig(Some(dv), None, None, None, None))),
          None,
          Some("doc"),
          None,
          None
        )
      )
    )

    val dynamic = ComponentsUiConfig(
      Map(
        "service" -> SingleComponentConfig(
          Some(Map("a" -> "xx", "c" -> "z").mapValuesNow(dv => ParameterConfig(Some(dv), None, None, None, None))),
          None,
          Some("doc1"),
          None,
          None
        )
      )
    )

    val expected = ComponentsUiConfig(
      Map(
        "service" -> SingleComponentConfig(
          Some(
            Map("a" -> "x", "b" -> "y", "c" -> "z").mapValuesNow(dv =>
              ParameterConfig(Some(dv), None, None, None, None)
            )
          ),
          None,
          Some("doc"),
          None,
          None
        )
      )
    )

    ComponentDefinitionPreparer.combineComponentsConfig(fixed, dynamic) shouldBe expected
  }

  private def validateGroups(groups: List[ComponentGroup], expectedSizeOfNotEmptyGroups: Int): Unit = {
    groups.filterNot(ng => ng.components.isEmpty) should have size expectedSizeOfNotEmptyGroups
  }

  private def prepareGroups(
      fixedConfig: Map[String, String],
      componentsGroupMapping: Map[ComponentGroupName, Option[ComponentGroupName]],
      modelDefinition: ModelDefinition[ComponentStaticDefinition] = ProcessTestData.modelDefinition
  ): List[ComponentGroup] = {
    // TODO: this is a copy paste from UIProcessObjectsFactory.prepareUIProcessObjects - should be refactored somehow
    val fragmentInputs = Map[String, FragmentStaticDefinition]()
    val dynamicComponentsConfig = ComponentsUiConfig(modelDefinition.components.map { case (info, value) =>
      info.name -> value.componentConfig
    })
    val fixedComponentsConfig =
      ComponentsUiConfig(
        fixedConfig.mapValuesNow(v => SingleComponentConfig(None, None, None, Some(ComponentGroupName(v)), None))
      )
    val componentsConfig =
      ComponentDefinitionPreparer.combineComponentsConfig(fixedComponentsConfig, dynamicComponentsConfig)

    val groups = ComponentDefinitionPreparer.prepareComponentsGroupList(
      user = TestFactory.adminUser("aa"),
      modelDefinition = modelDefinition,
      fragmentComponents = fragmentInputs,
      isFragment = false,
      componentsConfig = componentsConfig,
      componentsGroupMapping = componentsGroupMapping,
      processCategoryService = processCategoryService,
      TestProcessingTypes.Streaming
    )
    groups
  }

  private def prepareGroupsOfNodes(services: List[String]): List[ComponentGroup] = {
    val modelDefinition = services
      .foldRight(ModelDefinitionBuilder.empty)((s, p) => p.withService(s))
    val groups = ComponentDefinitionPreparer.prepareComponentsGroupList(
      user = TestFactory.adminUser("aa"),
      modelDefinition = modelDefinition,
      fragmentComponents = Map.empty,
      isFragment = false,
      componentsConfig = ComponentsUiConfig(Map.empty),
      componentsGroupMapping = Map(),
      processCategoryService = processCategoryService,
      TestProcessingTypes.Streaming
    )
    groups
  }

}
