package pl.touk.nussknacker.ui.process.processingtypedata

import pl.touk.nussknacker.engine.api.process.ProcessingType
import pl.touk.nussknacker.engine.util.Implicits.RichScalaMap
import pl.touk.nussknacker.security.Permission
import pl.touk.nussknacker.ui.UnauthorizedError
import pl.touk.nussknacker.ui.process.processingtypedata.ValueAccessPermission.{AnyUser, UserWithAccessRightsToCategory}
import pl.touk.nussknacker.ui.security.api.LoggedUser

import java.util.concurrent.atomic.AtomicReference

/**
  * ProcessingType is a context of application. One ProcessingType can't see data from another ProcessingType.
  * Services run inside one ProcessingType scope behave differently from services run in another scope.
  *
  * This class is meant to provide access to some scope of data inside context of application to the user.
  * We don't want to pass all ProcessingType's data to every service because it would complicate testing of services
  * and would broke isolation between areas of application. Due to that, this class is a `Functor`
  * (to be precise `BiFunctor` but more on that below) which allows to transform the scope of `Data`.
  *
  * Sometimes it is necessary to have access also to combination of data across all ProcessingTypes. Due to that
  * this class is a `BiFunctor` which second value named as `CombinedData`
  *
  * This class caches `Data` and `CombinedData` wrapped in `ProcessingTypeDataState` to avoid computations of
  * transformations during each lookup to `Data`/`CombinedData`. It behave similar to `Observable` where given
  * transformed `ProcessingTypeDataProvider` check its parent if `ProcessingTypeDataState.stateIdentity` changed.
  *
  * ProcessingType is associated with Category e.g. Fraud Detection, Marketing. Given user has access to certain
  * categories see `LoggedUser.can`. Due to that, during each access to `Data`, user is authorized if he/she
  * has access to category.
  */
trait ProcessingTypeDataProvider[+Data, +CombinedData] {

  // TODO: replace with proper forType handling
  final def forTypeUnsafe(processingType: ProcessingType)(implicit user: LoggedUser): Data = forType(processingType)
    .getOrElse(
      throw new IllegalArgumentException(
        s"Unknown ProcessingType: $processingType, known ProcessingTypes are: ${all.keys.mkString(", ")}"
      )
    )

  final def forType(processingType: ProcessingType)(implicit user: LoggedUser): Option[Data] = allAuthorized
    .get(processingType)
    .map(_.getOrElse(throw new UnauthorizedError()))

  final def all(implicit user: LoggedUser): Map[ProcessingType, Data] = allAuthorized.collect { case (k, Some(v)) =>
    (k, v)
  }

  private def allAuthorized(implicit user: LoggedUser): Map[ProcessingType, Option[Data]] = state.all.map {
    case (k, ValueWithPermission(v, AnyUser)) => (k, Some(v))
    case (k, ValueWithPermission(v, UserWithAccessRightsToCategory(category))) if user.can(category, Permission.Read) =>
      (k, Some(v))
    case (k, _) => (k, None)
  }

  // TODO: We should return a generic type that can produce views for users with access rights to certain categories only.
  //       Thanks to that we will be sure that no sensitive data leak
  final def combined: CombinedData = state.getCombined()

  private[processingtypedata] def state: ProcessingTypeDataState[Data, CombinedData]

  final def mapValues[TT](fun: Data => TT): ProcessingTypeDataProvider[TT, CombinedData] =
    new TransformingProcessingTypeDataProvider[Data, CombinedData, TT, CombinedData](this, _.mapValues(fun))

  final def mapCombined[CC](fun: CombinedData => CC): ProcessingTypeDataProvider[Data, CC] =
    new TransformingProcessingTypeDataProvider[Data, CombinedData, Data, CC](this, _.mapCombined(fun))

}

private[processingtypedata] class TransformingProcessingTypeDataProvider[T, C, TT, CC](
    observed: ProcessingTypeDataProvider[T, C],
    transformState: ProcessingTypeDataState[T, C] => ProcessingTypeDataState[TT, CC]
) extends ProcessingTypeDataProvider[TT, CC] {

  private val stateValue = new AtomicReference(transformState(observed.state))

  override private[processingtypedata] def state: ProcessingTypeDataState[TT, CC] = {
    stateValue.updateAndGet { currentValue =>
      val currentObservedState = observed.state
      if (currentObservedState.stateIdentity != currentValue.stateIdentity) {
        transformState(currentObservedState)
      } else {
        currentValue
      }
    }
  }

}

object ProcessingTypeDataProvider {

  val noCombinedDataFun: () => Nothing = () =>
    throw new IllegalStateException(
      "Processing type data provider does not have combined data!"
    )

  def apply[T, C](stateValue: ProcessingTypeDataState[T, C]): ProcessingTypeDataProvider[T, C] =
    new ProcessingTypeDataProvider[T, C] {
      override private[processingtypedata] def state: ProcessingTypeDataState[T, C] = stateValue
    }

  def apply[T, C](
      allValues: Map[ProcessingType, ValueWithPermission[T]],
      combinedValue: C
  ): ProcessingTypeDataProvider[T, C] =
    new ProcessingTypeDataProvider[T, C] {

      override private[processingtypedata] val state: ProcessingTypeDataState[T, C] = ProcessingTypeDataState(
        allValues,
        () => combinedValue,
        allValues
      )

    }

  def withEmptyCombinedData[T](
      allValues: Map[ProcessingType, ValueWithPermission[T]]
  ): ProcessingTypeDataProvider[T, Nothing] =
    new ProcessingTypeDataProvider[T, Nothing] {

      override private[processingtypedata] val state: ProcessingTypeDataState[T, Nothing] = ProcessingTypeDataState(
        allValues,
        noCombinedDataFun,
        allValues
      )

    }

}

// It keeps a state (Data and CombinedData) that is cached and restricted by ProcessingTypeDataProvider
trait ProcessingTypeDataState[+Data, +CombinedData] {
  def all: Map[ProcessingType, ValueWithPermission[Data]]

  // It returns function because we want to sometimes throw Exception instead of return value and we want to
  // transform values without touch combined part
  def getCombined: () => CombinedData

  // We keep stateIdentity as a separate value to avoid frequent computation of this.all.equals(that.all)
  // Also, it is easier to provide one (source) state identity than provide it for all observers
  def stateIdentity: Any

  final def mapValues[TT](fun: Data => TT): ProcessingTypeDataState[TT, CombinedData] =
    ProcessingTypeDataState[TT, CombinedData](all.mapValuesNow(_.map(fun)), getCombined, stateIdentity)

  final def mapCombined[CC](fun: CombinedData => CC): ProcessingTypeDataState[Data, CC] = {
    val newCombined = fun(getCombined())
    ProcessingTypeDataState[Data, CC](all, () => newCombined, stateIdentity)
  }

}

object ProcessingTypeDataState {

  def apply[Data, CombinedData](
      allValues: Map[ProcessingType, ValueWithPermission[Data]],
      getCombinedValue: () => CombinedData,
      stateIdentityValue: Any
  ): ProcessingTypeDataState[Data, CombinedData] =
    new ProcessingTypeDataState[Data, CombinedData] {
      override def all: Map[ProcessingType, ValueWithPermission[Data]] = allValues
      override def getCombined: () => CombinedData                     = getCombinedValue
      override def stateIdentity: Any                                  = stateIdentityValue
    }

}

final case class ValueWithPermission[+T](value: T, permission: ValueAccessPermission) {
  def map[TT](fun: T => TT): ValueWithPermission[TT] = copy(value = fun(value))
}

object ValueWithPermission {
  def anyUser[T](value: T): ValueWithPermission[T] = ValueWithPermission(value, AnyUser)
}

sealed trait ValueAccessPermission

object ValueAccessPermission {
  // This permission is mainly to provide easier testing where we want to simulate simple setup without
  // `ProcessingTypeData` reload, without category access control.
  case object AnyUser                                               extends ValueAccessPermission
  final case class UserWithAccessRightsToCategory(category: String) extends ValueAccessPermission
}
