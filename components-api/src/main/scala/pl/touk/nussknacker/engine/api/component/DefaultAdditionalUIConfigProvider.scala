package pl.touk.nussknacker.engine.api.component

class DefaultAdditionalUIConfigProvider(
    processingTypeToConfig: Map[String, Map[ComponentId, ComponentAdditionalConfig]],
    processingTypeToAdditionalPropertiesConfig: Map[String, Map[String, ScenarioPropertyConfig]],
) extends AdditionalUIConfigProvider {

  override def getAllForProcessingType(processingType: String): Map[ComponentId, ComponentAdditionalConfig] =
    processingTypeToConfig.getOrElse(processingType, Map.empty)

  override def getScenarioPropertiesUIConfigs(processingType: String): Map[String, ScenarioPropertyConfig] =
    processingTypeToAdditionalPropertiesConfig.getOrElse(processingType, Map.empty)
}
