package pl.touk.nussknacker.ui.component

import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.engine.ProcessingTypeData
import pl.touk.nussknacker.engine.api.process.ProcessingType
import pl.touk.nussknacker.ui.process.ProcessCategoryService

object ComponentIdProviderFactory extends LazyLogging {

  def createUnsafe(
      processingTypeDataMap: Map[ProcessingType, ProcessingTypeData],
      categoryService: ProcessCategoryService
  ): ComponentIdProvider = {
    logger.debug("Creating component id provider")

    val componentObjectsService = new ComponentObjectsService(categoryService)
    val componentObjectsMap =
      processingTypeDataMap.transform(componentObjectsService.prepareWithoutFragmentsAndAdditionalUIConfigs)
    val componentIdProvider = new DefaultComponentIdProvider(componentObjectsMap.transform {
      case (_, componentsObjects) => componentsObjects.config
    })

    ComponentsValidator.checkUnsafe(componentObjectsMap, componentIdProvider)

    componentIdProvider
  }

}
