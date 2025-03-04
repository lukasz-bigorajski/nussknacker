package pl.touk.nussknacker.ui.component

import pl.touk.nussknacker.engine.ProcessingTypeData
import pl.touk.nussknacker.engine.api.component.{AdditionalUIConfigProvider, ComponentGroupName}
import pl.touk.nussknacker.engine.api.process.ProcessingType
import pl.touk.nussknacker.engine.modelconfig.ComponentsUiConfig
import pl.touk.nussknacker.restmodel.definition.{ComponentNodeTemplate, UIProcessObjects}
import pl.touk.nussknacker.ui.definition.UIProcessObjectsFactory
import pl.touk.nussknacker.ui.process.ProcessCategoryService
import pl.touk.nussknacker.ui.process.fragment.FragmentDetails
import pl.touk.nussknacker.ui.security.api.{LoggedUser, NussknackerInternalUser}

private[component] final case class ComponentObjects(
    // TODO: We shouldn't base on ComponentTemplate here - it is final DTO which should be used only for encoding
    templates: List[(ComponentGroupName, ComponentNodeTemplate)],
    config: ComponentsUiConfig
)

private[component] object ComponentObjects {

  def apply(uIProcessObjects: UIProcessObjects): ComponentObjects = {
    val templates =
      uIProcessObjects.componentGroups.flatMap(group => group.components.map(component => (group.name, component)))
    ComponentObjects(templates, new ComponentsUiConfig(uIProcessObjects.componentsConfig))
  }

}

/**
 * TODO: Right now we use UIProcessObjectsFactory for extract components data, because there is assigned logic
 * responsible for: hiding, mapping group name, etc.. We should move this logic to another place, because
 * UIProcessObjectsFactory does many other things, things that we don't need here..
 */
private[component] class ComponentObjectsService(categoryService: ProcessCategoryService) {

  def prepareWithoutFragmentsAndAdditionalUIConfigs(
      processingType: ProcessingType,
      processingTypeData: ProcessingTypeData
  ): ComponentObjects = {
    val uiProcessObjects = createUIProcessObjects(
      processingType,
      processingTypeData,
      user = NussknackerInternalUser.instance, // We need admin user to receive all components info
      fragments = List.empty,
      additionalUIConfigProvider =
        AdditionalUIConfigProvider.empty // this method is only used in ComponentIdProviderFactory, and because AdditionalUIConfigProvider can't change ComponentId, we don't need it
    )
    ComponentObjects(uiProcessObjects)
  }

  def prepare(
      processingType: ProcessingType,
      processingTypeData: ProcessingTypeData,
      user: LoggedUser,
      fragments: List[FragmentDetails],
      additionalUIConfigProvider: AdditionalUIConfigProvider
  ): ComponentObjects = {
    val uiProcessObjects =
      createUIProcessObjects(processingType, processingTypeData, user, fragments, additionalUIConfigProvider)
    ComponentObjects(uiProcessObjects)
  }

  private def createUIProcessObjects(
      processingType: ProcessingType,
      processingTypeData: ProcessingTypeData,
      user: LoggedUser,
      fragments: List[FragmentDetails],
      additionalUIConfigProvider: AdditionalUIConfigProvider
  ): UIProcessObjects = {
    UIProcessObjectsFactory.prepareUIProcessObjects(
      modelDataForType = processingTypeData.modelData,
      modelDefinition = processingTypeData.staticModelDefinition,
      deploymentManager = processingTypeData.deploymentManager,
      user = user,
      fragmentsDetails = fragments,
      isFragment = false, // It excludes fragment's components: input / output
      processCategoryService = categoryService,
      scenarioPropertiesConfig = processingTypeData.scenarioPropertiesConfig,
      processingType = processingType,
      additionalUIConfigProvider = additionalUIConfigProvider
    )
  }

}
