package pl.touk.nussknacker.engine.standalone.utils.logging

import akka.event.Logging
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.directives.DebuggingDirectives
import akka.stream.ActorMaterializer
import cats.data.NonEmptyList
import pl.touk.nussknacker.engine.util.loader.ScalaServiceLoader
import pl.touk.nussknacker.engine.util.multiplicity.{Empty, Many, Multiplicity, One}

trait StandaloneRequestResponseLogger {
  def loggingDirective(processName: String)(implicit mat: ActorMaterializer): Directive0
}

object StandaloneRequestResponseLogger {
  def get(classLoader: ClassLoader): StandaloneRequestResponseLogger = {
    compound {
      Multiplicity(ScalaServiceLoader.load[StandaloneRequestResponseLogger](classLoader)) match {
        case One(logger) => NonEmptyList.of(logger)
        case Many(loggers) => NonEmptyList.fromListUnsafe(loggers)
        case Empty() => NonEmptyList.of(StandaloneRequestResponseLogger.default)
      }
    }
  }

  private def compound(loggers: NonEmptyList[StandaloneRequestResponseLogger]): StandaloneRequestResponseLogger = {
    new StandaloneRequestResponseLogger {
      override def loggingDirective(processName: String)(implicit mat: ActorMaterializer): Directive0 = {
        loggers.map(_.loggingDirective(processName)).reduceLeft(_ & _)
      }
    }
  }

  def default: StandaloneRequestResponseLogger = new StandaloneRequestResponseLogger {
    override def loggingDirective(processName: String)(implicit mat: ActorMaterializer): Directive0 = {
      DebuggingDirectives.logRequestResult((s"standalone-$processName", Logging.DebugLevel))
    }
  }
}