package pl.touk.nussknacker.engine.compile.nodecompilation

import pl.touk.nussknacker.engine.api.context.transformation._
import pl.touk.nussknacker.engine.api.definition.{AdditionalVariableWithFixedValue, Parameter => ParameterDef}
import pl.touk.nussknacker.engine.api.expression.{TypedExpression, TypedExpressionMap}
import pl.touk.nussknacker.engine.api.{Context, MetaData, NodeId}
import pl.touk.nussknacker.engine.compiledgraph.{CompiledParameter, TypedParameter}
import pl.touk.nussknacker.engine.expression.ExpressionEvaluator
import pl.touk.nussknacker.engine.graph
import pl.touk.nussknacker.engine.util.Implicits._

class ParameterEvaluator(expressionEvaluator: ExpressionEvaluator) {

  private val contextToUse: Context = Context("objectCreate")

  def prepareParameter(
      typedParameter: TypedParameter,
      definition: ParameterDef
  )(implicit processMetaData: MetaData, nodeId: NodeId): (AnyRef, BaseDefinedParameter) = {
    if (definition.isLazyParameter) {
      prepareLazyParameter(typedParameter, definition)
    } else {
      evaluateParam(typedParameter, definition)
    }
  }

  private def prepareLazyParameter[T](param: TypedParameter, definition: ParameterDef)(
      implicit nodeId: NodeId
  ): (AnyRef, BaseDefinedParameter) = {
    param.typedValue match {
      case e: TypedExpression if !definition.branchParam =>
        (prepareLazyParameterExpression(definition, e), DefinedLazyParameter(e))
      case TypedExpressionMap(valueByKey) if definition.branchParam =>
        (valueByKey.mapValuesNow(prepareLazyParameterExpression(definition, _)), DefinedLazyBranchParameter(valueByKey))
      case _ => throw new IllegalStateException()
    }
  }

  private def evaluateParam[T](
      param: TypedParameter,
      definition: ParameterDef
  )(implicit processMetaData: MetaData, nodeId: NodeId): (AnyRef, BaseDefinedParameter) = {

    val additionaldefinitions = definition.additionalVariables.collect {
      case (name, AdditionalVariableWithFixedValue(value, _)) =>
        name -> value
    }
    val augumentedCtx = contextToUse.withVariables(additionaldefinitions)

    param.typedValue match {
      case e: TypedExpression if !definition.branchParam =>
        val evaluated = evaluateSync(CompiledParameter(e, definition), augumentedCtx)
        (evaluated, DefinedEagerParameter(evaluated, e))
      case TypedExpressionMap(valueByKey) if definition.branchParam =>
        val evaluated = valueByKey.mapValuesNow(exp => evaluateSync(CompiledParameter(exp, definition), augumentedCtx))
        (evaluated, DefinedEagerBranchParameter(evaluated, valueByKey))
      case _ => throw new IllegalStateException()
    }
  }

  private def prepareLazyParameterExpression[T](definition: ParameterDef, exprValue: TypedExpression)(
      implicit nodeId: NodeId
  ): ExpressionLazyParameter[Nothing] = {
    ExpressionLazyParameter(
      nodeId,
      definition,
      graph.expression.Expression(exprValue.expression.language, exprValue.expression.original),
      exprValue.returnType
    )
  }

  private def evaluateSync(
      param: CompiledParameter,
      ctx: Context
  )(implicit processMetaData: MetaData, nodeId: NodeId): AnyRef = {
    expressionEvaluator.evaluateParameter(param, ctx).value
  }

}
