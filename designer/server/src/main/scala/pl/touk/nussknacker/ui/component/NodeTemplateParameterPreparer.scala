package pl.touk.nussknacker.ui.component

import pl.touk.nussknacker.engine.api.definition.Parameter
import pl.touk.nussknacker.engine.graph.evaluatedparam.{Parameter => NodeParameter}
import pl.touk.nussknacker.engine.graph.expression.Expression

object NodeTemplateParameterPreparer {

  def prepareNodeTemplateParameter(parameterDefinitions: List[Parameter]): List[NodeParameter] = {
    parameterDefinitions
      .filterNot(_.branchParam)
      .map(createNodeParameterWithDefaultValue)
  }

  def prepareNodeTemplateBranchParameter(parameterDefinitions: List[Parameter]): List[NodeParameter] = {
    parameterDefinitions
      .filter(_.branchParam)
      .map(createNodeParameterWithDefaultValue)
  }

  private def createNodeParameterWithDefaultValue(parameterDefinition: Parameter): NodeParameter =
    NodeParameter(parameterDefinition.name, parameterDefinition.defaultValue.getOrElse(Expression.spel("")))
}
