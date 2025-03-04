package pl.touk.nussknacker.engine.compile.nodecompilation

import cats.data.Validated.{Invalid, Valid, invalid, valid}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits.toTraverseOps
import cats.instances.list._
import pl.touk.nussknacker.engine.api._
import pl.touk.nussknacker.engine.api.component.ComponentType
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError._
import pl.touk.nussknacker.engine.api.context._
import pl.touk.nussknacker.engine.api.context.transformation.{
  JoinGenericNodeTransformation,
  SingleInputGenericNodeTransformation
}
import pl.touk.nussknacker.engine.api.definition.Parameter
import pl.touk.nussknacker.engine.api.expression.{
  Expression => CompiledExpression,
  ExpressionParser,
  ExpressionTypingInfo,
  TypedExpression,
  TypedExpressionMap
}
import pl.touk.nussknacker.engine.api.process.{ComponentUseCase, Source}
import pl.touk.nussknacker.engine.api.typed.ReturningType
import pl.touk.nussknacker.engine.api.typed.typing.{TypingResult, Unknown}
import pl.touk.nussknacker.engine.compile.nodecompilation.NodeCompiler.NodeCompilationResult
import pl.touk.nussknacker.engine.compile.{
  ComponentExecutorFactory,
  ExpressionCompiler,
  FragmentSourceWithTestWithParametersSupportFactory,
  NodeValidationExceptionHandler
}
import pl.touk.nussknacker.engine.compiledgraph.{CompiledParameter, TypedParameter}
import pl.touk.nussknacker.engine.definition.component.ComponentDefinitionWithImplementation
import pl.touk.nussknacker.engine.definition.component.dynamic.{
  DynamicComponentDefinitionWithImplementation,
  FinalStateValue
}
import pl.touk.nussknacker.engine.definition.component.methodbased.MethodBasedComponentDefinitionWithImplementation
import pl.touk.nussknacker.engine.definition.fragment.FragmentCompleteDefinitionExtractor
import pl.touk.nussknacker.engine.definition.globalvariables.ExpressionConfigDefinition
import pl.touk.nussknacker.engine.definition.model.ModelDefinition
import pl.touk.nussknacker.engine.expression.ExpressionEvaluator
import pl.touk.nussknacker.engine.graph.evaluatedparam.{BranchParameters, Parameter => NodeParameter}
import pl.touk.nussknacker.engine.graph.expression.NodeExpressionId.branchParameterExpressionId
import pl.touk.nussknacker.engine.graph.expression._
import pl.touk.nussknacker.engine.graph.node._
import pl.touk.nussknacker.engine.graph.service.ServiceRef
import pl.touk.nussknacker.engine.resultcollector.ResultCollector
import pl.touk.nussknacker.engine.variables.GlobalVariablesPreparer
import pl.touk.nussknacker.engine.{api, compiledgraph}
import shapeless.Typeable
import shapeless.syntax.typeable._

object NodeCompiler {

  case class NodeCompilationResult[T](
      expressionTypingInfo: Map[String, ExpressionTypingInfo],
      parameters: Option[List[Parameter]],
      validationContext: ValidatedNel[ProcessCompilationError, ValidationContext],
      compiledObject: ValidatedNel[ProcessCompilationError, T],
      expressionType: Option[TypingResult] = None
  ) {
    def errors: List[ProcessCompilationError] =
      (validationContext.swap.toList ++ compiledObject.swap.toList).flatMap(_.toList)

    def map[R](f: T => R): NodeCompilationResult[R] = copy(compiledObject = compiledObject.map(f))

  }

}

class NodeCompiler(
    definitions: ModelDefinition[ComponentDefinitionWithImplementation],
    fragmentDefinitionExtractor: FragmentCompleteDefinitionExtractor,
    expressionCompiler: ExpressionCompiler,
    classLoader: ClassLoader,
    resultCollector: ResultCollector,
    componentUseCase: ComponentUseCase
) {

  def withExpressionParsers(modify: PartialFunction[ExpressionParser, ExpressionParser]): NodeCompiler = {
    new NodeCompiler(
      definitions,
      fragmentDefinitionExtractor,
      expressionCompiler.withExpressionParsers(modify),
      classLoader,
      resultCollector,
      componentUseCase
    )
  }

  type GenericValidationContext = Either[ValidationContext, Map[String, ValidationContext]]

  private lazy val globalVariablesPreparer          = GlobalVariablesPreparer(expressionConfig)
  private implicit val typeableJoin: Typeable[Join] = Typeable.simpleTypeable(classOf[Join])
  private val expressionConfig: ExpressionConfigDefinition[ComponentDefinitionWithImplementation] =
    definitions.expressionConfig

  private val expressionEvaluator =
    ExpressionEvaluator.unOptimizedEvaluator(globalVariablesPreparer)
  private val parametersEvaluator = new ParameterEvaluator(expressionEvaluator)
  private val factory             = new ComponentExecutorFactory(parametersEvaluator)
  private val dynamicNodeValidator =
    new DynamicNodeValidator(expressionCompiler, globalVariablesPreparer, parametersEvaluator)
  private val builtInNodeCompiler = new BuiltInNodeCompiler(expressionCompiler)

  def compileSource(
      nodeData: SourceNodeData
  )(implicit metaData: MetaData, nodeId: NodeId): NodeCompilationResult[Source] = nodeData match {
    case a @ Source(_, ref, _) =>
      definitions.getComponent(ComponentType.Source, ref.typ) match {
        case Some(definition) =>
          def defaultCtxForMethodBasedCreatedComponentExecutor(
              returnType: Option[TypingResult]
          ) =
            contextWithOnlyGlobalVariables.withVariable(
              VariableConstants.InputVariableName,
              returnType.getOrElse(Unknown),
              paramName = None
            )

          compileComponentWithContextTransformation[Source](
            a.parameters,
            Nil,
            Left(contextWithOnlyGlobalVariables),
            Some(VariableConstants.InputVariableName),
            definition,
            defaultCtxForMethodBasedCreatedComponentExecutor
          ).map(_._1)
        case None =>
          val error = Invalid(NonEmptyList.of(MissingSourceFactory(ref.typ)))
          // TODO: is this default behaviour ok?
          val defaultCtx =
            contextWithOnlyGlobalVariables.withVariable(VariableConstants.InputVariableName, Unknown, paramName = None)
          NodeCompilationResult(Map.empty, None, defaultCtx, error)
      }
    case frag @ FragmentInputDefinition(id, _, _) =>
      val parameterDefinitions =
        fragmentDefinitionExtractor.extractParametersDefinition(frag, contextWithOnlyGlobalVariables)
      val variables: Map[String, TypingResult] = parameterDefinitions.value.map(a => a.name -> a.typ).toMap
      val validationContext                    = contextWithOnlyGlobalVariables.copy(localVariables = variables)

      val compilationResult = definitions.getComponent(ComponentType.Fragment, id) match {
        // This case is when fragment is stubbed with test data
        case Some(definition) =>
          compileComponentWithContextTransformation[Source](
            Nil,
            Nil,
            Left(contextWithOnlyGlobalVariables),
            None,
            definition,
            _ => Valid(validationContext)
          ).map(_._1)

        // For default case, we creates source that support test with parameters
        case None =>
          NodeCompilationResult(
            Map.empty,
            None,
            Valid(validationContext),
            Valid(new FragmentSourceWithTestWithParametersSupportFactory(parameterDefinitions.value).createSource())
          )
      }

      val parameterExtractionValidation =
        NonEmptyList.fromList(parameterDefinitions.written).map(invalid).getOrElse(valid(List.empty))

      compilationResult.copy(compiledObject =
        parameterExtractionValidation.andThen(_ => compilationResult.compiledObject)
      )
  }

  def compileCustomNodeObject(data: CustomNodeData, ctx: GenericValidationContext, ending: Boolean)(
      implicit metaData: MetaData,
      nodeId: NodeId
  ): NodeCompilationResult[AnyRef] = {

    val outputVar       = data.outputVar.map(OutputVar.customNode)
    val defaultCtx      = ctx.fold(identity, _ => contextWithOnlyGlobalVariables)
    val defaultCtxToUse = outputVar.map(defaultCtx.withVariable(_, Unknown)).getOrElse(Valid(defaultCtx))

    definitions.getComponent(ComponentType.CustomComponent, data.nodeType) match {
      case Some(componentDefinitionWithImpl)
          if ending && !componentDefinitionWithImpl.componentTypeSpecificData.asCustomComponentData.canBeEnding =>
        val error = Invalid(NonEmptyList.of(InvalidTailOfBranch(nodeId.id)))
        NodeCompilationResult(Map.empty, None, defaultCtxToUse, error)
      case Some(componentDefinitionWithImpl) =>
        val default = defaultContextAfter(data, ending, ctx)
        compileComponentWithContextTransformation[AnyRef](
          data.parameters,
          data.cast[Join].map(_.branchParameters).getOrElse(Nil),
          ctx,
          outputVar.map(_.outputName),
          componentDefinitionWithImpl,
          default
        ).map(_._1)
      case None =>
        val error = Invalid(NonEmptyList.of(MissingCustomNodeExecutor(data.nodeType)))
        NodeCompilationResult(Map.empty, None, defaultCtxToUse, error)
    }
  }

  def compileSink(
      sink: Sink,
      ctx: ValidationContext
  )(implicit nodeId: NodeId, metaData: MetaData): NodeCompilationResult[api.process.Sink] = {
    val ref = sink.ref

    definitions.getComponent(ComponentType.Sink, ref.typ) match {
      case Some(definition) =>
        compileComponentWithContextTransformation[api.process.Sink](
          sink.parameters,
          Nil,
          Left(ctx),
          None,
          definition,
          _ => Valid(ctx)
        ).map(_._1)
      case None =>
        val error = invalid(MissingSinkFactory(sink.ref.typ)).toValidatedNel
        NodeCompilationResult(Map.empty[String, ExpressionTypingInfo], None, Valid(ctx), error)
    }
  }

  def compileFragmentInput(fragmentInput: FragmentInput, ctx: ValidationContext)(
      implicit nodeId: NodeId
  ): NodeCompilationResult[List[CompiledParameter]] = {

    val ref            = fragmentInput.ref
    val validParamDefs = fragmentDefinitionExtractor.extractParametersDefinition(fragmentInput, ctx)

    val childCtx = ctx.pushNewContext()
    val newCtx =
      validParamDefs.value.foldLeft[ValidatedNel[ProcessCompilationError, ValidationContext]](Valid(childCtx)) {
        case (acc, paramDef) => acc.andThen(_.withVariable(OutputVar.variable(paramDef.name), paramDef.typ))
      }
    val validParams =
      expressionCompiler.compileExecutorComponentNodeParameters(validParamDefs.value, ref.parameters, ctx)
    val validParamsCombinedErrors = validParams.combine(
      NonEmptyList
        .fromList(validParamDefs.written)
        .map(invalid)
        .getOrElse(valid(List.empty[CompiledParameter]))
    )
    val expressionTypingInfo =
      validParams.map(_.map(p => p.name -> p.typingInfo).toMap).valueOr(_ => Map.empty[String, ExpressionTypingInfo])
    NodeCompilationResult(expressionTypingInfo, None, newCtx, validParamsCombinedErrors)
  }

  // expression is deprecated, will be removed in the future
  def compileSwitch(
      expressionRaw: Option[(String, Expression)],
      choices: List[(String, Expression)],
      ctx: ValidationContext
  )(
      implicit nodeId: NodeId
  ): NodeCompilationResult[(Option[CompiledExpression], List[CompiledExpression])] = {
    builtInNodeCompiler.compileSwitch(expressionRaw, choices, ctx)
  }

  def compileFilter(filter: Filter, ctx: ValidationContext)(
      implicit nodeId: NodeId
  ): NodeCompilationResult[CompiledExpression] = {
    builtInNodeCompiler.compileFilter(filter, ctx)
  }

  def compileVariable(variable: Variable, ctx: ValidationContext)(
      implicit nodeId: NodeId
  ): NodeCompilationResult[CompiledExpression] = {
    builtInNodeCompiler.compileVariable(variable, ctx)
  }

  def fieldToTypedExpression(fields: List[pl.touk.nussknacker.engine.graph.variable.Field], ctx: ValidationContext)(
      implicit nodeId: NodeId
  ): ValidatedNel[PartSubGraphCompilationError, Map[String, TypedExpression]] = {
    fields.map { field =>
      expressionCompiler
        .compile(field.expression, Some(field.name), ctx, Unknown)
        .map(typedExpr => field.name -> typedExpr)
    }
  }.sequence.map(_.toMap)

  def compileFields(
      fields: List[pl.touk.nussknacker.engine.graph.variable.Field],
      ctx: ValidationContext,
      outputVar: Option[OutputVar]
  )(implicit nodeId: NodeId): NodeCompilationResult[List[compiledgraph.variable.Field]] = {
    builtInNodeCompiler.compileFields(fields, ctx, outputVar)
  }

  def compileProcessor(
      n: Processor,
      ctx: ValidationContext
  )(implicit nodeId: NodeId, metaData: MetaData): NodeCompilationResult[compiledgraph.service.ServiceRef] = {
    compileService(n.service, ctx, None)
  }

  def compileEnricher(n: Enricher, ctx: ValidationContext, outputVar: OutputVar)(
      implicit nodeId: NodeId,
      metaData: MetaData
  ): NodeCompilationResult[compiledgraph.service.ServiceRef] = {
    compileService(n.service, ctx, Some(outputVar))
  }

  private def compileService(n: ServiceRef, validationContext: ValidationContext, outputVar: Option[OutputVar])(
      implicit nodeId: NodeId,
      metaData: MetaData
  ): NodeCompilationResult[compiledgraph.service.ServiceRef] = {

    definitions.getComponent(ComponentType.Service, n.id) match {
      case Some(componentDefWithImpl) if componentDefWithImpl.implementation.isInstanceOf[EagerService] =>
        compileEagerService(n, componentDefWithImpl, validationContext, outputVar)
      case Some(static: MethodBasedComponentDefinitionWithImplementation) =>
        ServiceCompiler.compile(n, outputVar, static, validationContext)
      case Some(_: DynamicComponentDefinitionWithImplementation) =>
        val error = invalid(
          CustomNodeError(
            "Not supported service implementation: GenericNodeTransformation can be mixed only with EagerService",
            None
          )
        ).toValidatedNel
        NodeCompilationResult(Map.empty[String, ExpressionTypingInfo], None, Valid(validationContext), error)
      case Some(notSupportedComponentDefWithImpl) =>
        throw new IllegalStateException(
          s"Not supported ComponentDefinitionWithImplementation: ${notSupportedComponentDefWithImpl.getClass}"
        )
      case None =>
        val error = invalid(MissingService(n.id)).toValidatedNel
        NodeCompilationResult(Map.empty[String, ExpressionTypingInfo], None, Valid(validationContext), error)
    }
  }

  private def compileEagerService(
      serviceRef: ServiceRef,
      componentDefWithImpl: ComponentDefinitionWithImplementation,
      validationContext: ValidationContext,
      outputVar: Option[OutputVar]
  )(implicit nodeId: NodeId, metaData: MetaData): NodeCompilationResult[compiledgraph.service.ServiceRef] = {
    val defaultCtxForMethodBasedCreatedComponentExecutor
        : Option[TypingResult] => ValidatedNel[ProcessCompilationError, ValidationContext] = returnTypeOpt =>
      outputVar match {
        case Some(out) =>
          returnTypeOpt
            .map(Valid(_))
            .getOrElse(Invalid(NonEmptyList.of(RedundantParameters(Set("OutputVariable")))))
            .andThen(validationContext.withVariable(out, _))
        case None => Valid(validationContext)
      }

    def prepareCompiledLazyParameters(paramsDefs: List[Parameter], nodeParams: List[NodeParameter]) = {
      val nodeParamsMap = nodeParams.map(p => p.name -> p).toMap
      paramsDefs.collect {
        case paramDef if paramDef.isLazyParameter =>
          val compiledParam = (for {
            param <- nodeParamsMap.get(paramDef.name)
            compiled <- expressionCompiler
              .compileParam(param, validationContext, paramDef)
              .toOption
              .flatMap(_.typedValue.cast[TypedExpression])
          } yield compiled)
            .getOrElse(throw new IllegalArgumentException(s"$paramDef is not defined as TypedExpression"))
          CompiledParameter(compiledParam, paramDef)
      }
    }

    def makeInvoker(service: ServiceInvoker, nodeParams: List[NodeParameter], paramsDefs: List[Parameter]) =
      compiledgraph.service.ServiceRef(
        serviceRef.id,
        service,
        prepareCompiledLazyParameters(paramsDefs, nodeParams),
        resultCollector
      )

    val compilationResult = compileComponentWithContextTransformation[ServiceInvoker](
      serviceRef.parameters,
      Nil,
      Left(validationContext),
      outputVar.map(_.outputName),
      componentDefWithImpl,
      defaultCtxForMethodBasedCreatedComponentExecutor
    )
    compilationResult.map { case (invoker, nodeParams) =>
      // TODO: Currently in case of object compilation failures we prefer to create "dumb" service invoker, with empty parameters list
      //       instead of return Invalid - I assume that it is probably because of errors accumulation purpose.
      //       We should clean up this compilation process by some NodeCompilationResult refactor like introduction of WriterT monad transformer
      makeInvoker(invoker, nodeParams, compilationResult.parameters.getOrElse(List.empty))
    }
  }

  private def unwrapContextTransformation[T](value: Any): T = (value match {
    case ct: ContextTransformation => ct.implementation
    case a                         => a
  }).asInstanceOf[T]

  private def contextWithOnlyGlobalVariables(implicit metaData: MetaData): ValidationContext =
    globalVariablesPreparer.prepareValidationContextWithGlobalVariablesOnly(metaData)

  private def defaultContextAfter(
      node: CustomNodeData,
      ending: Boolean,
      branchCtx: GenericValidationContext
  )(
      implicit nodeId: NodeId,
      metaData: MetaData
  ): Option[TypingResult] => ValidatedNel[ProcessCompilationError, ValidationContext] =
    returnTypeOpt => {
      val validationContext = branchCtx.left.getOrElse(contextWithOnlyGlobalVariables)

      def ctxWithVar(outputVar: OutputVar, typ: TypingResult) = validationContext
        .withVariable(outputVar, typ)
        // ble... NonEmptyList is invariant...
        .asInstanceOf[ValidatedNel[ProcessCompilationError, ValidationContext]]

      (node.outputVar, returnTypeOpt) match {
        case (Some(varName), Some(typ)) => ctxWithVar(OutputVar.customNode(varName), typ)
        case (None, None)               => Valid(validationContext)
        case (Some(_), None)            => Invalid(NonEmptyList.of(RedundantParameters(Set("OutputVariable"))))
        case (None, Some(_)) if ending  => Valid(validationContext)
        case (None, Some(_))            => Invalid(NonEmptyList.of(MissingParameters(Set("OutputVariable"))))
      }
    }

  private def compileComponentWithContextTransformation[ComponentExecutor](
      parameters: List[NodeParameter],
      branchParameters: List[BranchParameters],
      ctx: GenericValidationContext,
      outputVar: Option[String],
      componentDefinitionWithImpl: ComponentDefinitionWithImplementation,
      defaultCtxForMethodBasedCreatedComponentExecutor: Option[TypingResult] => ValidatedNel[
        ProcessCompilationError,
        ValidationContext
      ]
  )(
      implicit metaData: MetaData,
      nodeId: NodeId
  ): NodeCompilationResult[(ComponentExecutor, List[NodeParameter])] = {
    componentDefinitionWithImpl match {
      case dynamicComponent: DynamicComponentDefinitionWithImplementation =>
        val afterValidation =
          validateDynamicTransformer(ctx, parameters, branchParameters, outputVar, dynamicComponent).map {
            case TransformationResult(Nil, computedParameters, outputContext, finalState, nodeParameters) =>
              val computedParameterNames = computedParameters.filterNot(_.branchParam).map(p => p.name)
              val withoutRedundant       = nodeParameters.filter(p => computedParameterNames.contains(p.name))
              val (typingInfo, validComponentExecutor) = createComponentExecutor[ComponentExecutor](
                componentDefinitionWithImpl,
                withoutRedundant,
                branchParameters,
                outputVar,
                ctx,
                computedParameters,
                Seq(FinalStateValue(finalState))
              )
              (
                typingInfo,
                Some(computedParameters),
                outputContext,
                validComponentExecutor.map((_, withoutRedundant))
              )
            case TransformationResult(h :: t, computedParameters, outputContext, _, _) =>
              // TODO: typing info here??
              (
                Map.empty[String, ExpressionTypingInfo],
                Some(computedParameters),
                outputContext,
                Invalid(NonEmptyList(h, t))
              )
          }
        NodeCompilationResult(
          afterValidation.map(_._1).valueOr(_ => Map.empty),
          afterValidation.map(_._2).valueOr(_ => None),
          afterValidation.map(_._3),
          afterValidation.andThen(_._4)
        )
      case staticComponent: MethodBasedComponentDefinitionWithImplementation =>
        val (typingInfo, validComponentExecutor) = createComponentExecutor[ComponentExecutor](
          componentDefinitionWithImpl,
          parameters,
          branchParameters,
          outputVar,
          ctx,
          staticComponent.parameters,
          Seq.empty
        )
        val nextCtx = validComponentExecutor.fold(
          _ => defaultCtxForMethodBasedCreatedComponentExecutor(staticComponent.returnType),
          executor =>
            contextAfterMethodBasedCreatedComponentExecutor(
              executor,
              ctx,
              (executor: ComponentExecutor) =>
                defaultCtxForMethodBasedCreatedComponentExecutor(returnType(staticComponent.returnType, executor))
            )
        )
        val unwrappedComponentExecutor =
          validComponentExecutor.map(unwrapContextTransformation[ComponentExecutor](_)).map((_, parameters))
        NodeCompilationResult(typingInfo, Some(staticComponent.parameters), nextCtx, unwrappedComponentExecutor)
    }
  }

  private def returnType(definitionReturnType: Option[TypingResult], componentExecutor: Any): Option[TypingResult] =
    componentExecutor match {
      case returningType: ReturningType =>
        Some(returningType.returnType)
      case _ =>
        definitionReturnType
    }

  private def createComponentExecutor[ComponentExecutor](
      componentDefinition: ComponentDefinitionWithImplementation,
      nodeParameters: List[NodeParameter],
      nodeBranchParameters: List[BranchParameters],
      outputVariableNameOpt: Option[String],
      ctxOrBranches: GenericValidationContext,
      parameterDefinitionsToUse: List[Parameter],
      additionalDependencies: Seq[AnyRef]
  )(
      implicit nodeId: NodeId,
      metaData: MetaData
  ): (Map[String, ExpressionTypingInfo], ValidatedNel[ProcessCompilationError, ComponentExecutor]) = {
    val ctx            = ctxOrBranches.left.getOrElse(contextWithOnlyGlobalVariables)
    val branchContexts = ctxOrBranches.getOrElse(Map.empty)

    val compiledObjectWithTypingInfo = expressionCompiler
      .compileNodeParameters(
        parameterDefinitionsToUse,
        nodeParameters,
        nodeBranchParameters,
        ctx,
        branchContexts
      )
      .andThen { compiledParameters =>
        factory
          .createComponentExecutor[ComponentExecutor](
            componentDefinition,
            compiledParameters,
            outputVariableNameOpt,
            additionalDependencies,
            componentUseCase
          )
          .map { componentExecutor =>
            val typingInfo = compiledParameters.flatMap {
              case (TypedParameter(name, TypedExpression(_, _, typingInfo)), _) =>
                List(name -> typingInfo)
              case (TypedParameter(paramName, TypedExpressionMap(valueByBranch)), _) =>
                valueByBranch.map { case (branch, TypedExpression(_, _, typingInfo)) =>
                  val expressionId = branchParameterExpressionId(paramName, branch)
                  expressionId -> typingInfo
                }
            }.toMap
            (typingInfo, componentExecutor)
          }
      }
    (compiledObjectWithTypingInfo.map(_._1).valueOr(_ => Map.empty), compiledObjectWithTypingInfo.map(_._2))
  }

  private def contextAfterMethodBasedCreatedComponentExecutor[ComponentExecutor](
      executor: ComponentExecutor,
      validationContexts: GenericValidationContext,
      handleNonContextTransformingExecutor: ComponentExecutor => ValidatedNel[
        ProcessCompilationError,
        ValidationContext
      ]
  )(implicit nodeId: NodeId, metaData: MetaData): ValidatedNel[ProcessCompilationError, ValidationContext] = {
    NodeValidationExceptionHandler.handleExceptionsInValidation {
      val contextTransformationDefOpt = executor.cast[AbstractContextTransformation].map(_.definition)
      (contextTransformationDefOpt, validationContexts) match {
        case (Some(transformation: ContextTransformationDef), Left(validationContext)) =>
          // copying global variables because custom transformation may override them -> TODO: in ValidationContext
          transformation.transform(validationContext).map(_.copy(globalVariables = validationContext.globalVariables))
        case (Some(transformation: JoinContextTransformationDef), Right(branchEndContexts)) =>
          // copying global variables because custom transformation may override them -> TODO: in ValidationContext
          transformation
            .transform(branchEndContexts)
            .map(_.copy(globalVariables = contextWithOnlyGlobalVariables.globalVariables))
        case (Some(transformation), ctx) =>
          Invalid(
            FatalUnknownError(s"Invalid ContextTransformation class $transformation for contexts: $ctx")
          ).toValidatedNel
        case (None, _) =>
          handleNonContextTransformingExecutor(executor)
      }
    }
  }

  private def validateDynamicTransformer(
      eitherSingleOrJoin: GenericValidationContext,
      parameters: List[NodeParameter],
      branchParameters: List[BranchParameters],
      outputVar: Option[String],
      dynamicDefinition: DynamicComponentDefinitionWithImplementation
  )(implicit metaData: MetaData, nodeId: NodeId): ValidatedNel[ProcessCompilationError, TransformationResult] =
    (dynamicDefinition.implementation, eitherSingleOrJoin) match {
      case (single: SingleInputGenericNodeTransformation[_], Left(singleCtx)) =>
        dynamicNodeValidator.validateNode(
          single,
          parameters,
          branchParameters,
          outputVar,
          dynamicDefinition.componentConfig
        )(
          singleCtx
        )
      case (join: JoinGenericNodeTransformation[_], Right(joinCtx)) =>
        dynamicNodeValidator.validateNode(
          join,
          parameters,
          branchParameters,
          outputVar,
          dynamicDefinition.componentConfig
        )(
          joinCtx
        )
      case (_: SingleInputGenericNodeTransformation[_], Right(_)) =>
        Invalid(
          NonEmptyList.of(CustomNodeError("Invalid scenario structure: single input component used as a join", None))
        )
      case (_: JoinGenericNodeTransformation[_], Left(_)) =>
        Invalid(
          NonEmptyList.of(
            CustomNodeError("Invalid scenario structure: join component used as with single, not named input", None)
          )
        )
    }

  // This class is extracted to separate object, as handling service needs serious refactor (see comment in ServiceReturningType), and we don't want
  // methods that will probably be replaced to be mixed with others
  object ServiceCompiler {

    def compile(
        n: ServiceRef,
        outputVar: Option[OutputVar],
        objWithMethod: MethodBasedComponentDefinitionWithImplementation,
        ctx: ValidationContext
    )(implicit metaData: MetaData, nodeId: NodeId): NodeCompilationResult[compiledgraph.service.ServiceRef] = {
      val computedParameters =
        expressionCompiler.compileExecutorComponentNodeParameters(objWithMethod.parameters, n.parameters, ctx)
      val outputCtx = outputVar match {
        case Some(output) =>
          objWithMethod.returnType
            .map(Valid(_))
            .getOrElse(Invalid(NonEmptyList.of(RedundantParameters(Set("OutputVariable")))))
            .andThen(ctx.withVariable(output, _))
        case None => Valid(ctx)
      }

      val serviceRef = computedParameters.map { params =>
        compiledgraph.service.ServiceRef(
          n.id,
          new MethodBasedServiceInvoker(metaData, nodeId, outputVar, objWithMethod),
          params,
          resultCollector
        )
      }
      val nodeTypingInfo = computedParameters.map(_.map(p => p.name -> p.typingInfo).toMap).getOrElse(Map.empty)
      NodeCompilationResult(nodeTypingInfo, None, outputCtx, serviceRef)
    }

  }

}
