package pl.touk.nussknacker.engine.testmode

import cats.Monad
import cats.implicits._
import pl.touk.nussknacker.engine.api.test.InvocationCollectors.{CollectableAction, ToCollect, TransmissionNames}
import pl.touk.nussknacker.engine.api.{Context, ContextId, NodeId}
import pl.touk.nussknacker.engine.resultcollector.ResultCollector

import scala.language.higherKinds

class TestServiceInvocationCollector(testRunId: TestRunId) extends ResultCollector {

  override def collectWithResponse[A, F[_]: Monad](
      contextId: ContextId,
      nodeId: NodeId,
      serviceRef: String,
      request: => ToCollect,
      mockValue: Option[A],
      action: => F[CollectableAction[A]],
      names: TransmissionNames
  ): F[A] = {
    mockValue match {
      case Some(mockVal) =>
        ResultsCollectingListenerHolder.updateResults(
          testRunId,
          _.updateExternalInvocationResult(nodeId.id, contextId, serviceRef, request)
        )
        mockVal.pure[F]
      case None =>
        action.map { case CollectableAction(resultToCollect, result) =>
          val invocationResult = Map("request" -> request, "response" -> resultToCollect())
          ResultsCollectingListenerHolder.updateResults(
            testRunId,
            _.updateExternalInvocationResult(nodeId.id, contextId, serviceRef, invocationResult)
          )
          result
        }
    }
  }

  def createSinkInvocationCollector(nodeId: String, ref: String): SinkInvocationCollector =
    new SinkInvocationCollector(testRunId, nodeId, ref)

}

//TODO: this should be somehow expressed via ResultCollector/TestServiceInvocationCollector
final class SinkInvocationCollector(runId: TestRunId, nodeId: String, ref: String) extends Serializable {

  def collect(context: Context, result: Any): Unit = {
    ResultsCollectingListenerHolder.updateResults(
      runId,
      _.updateExternalInvocationResult(nodeId, ContextId(context.id), ref, result)
    )
  }

}
