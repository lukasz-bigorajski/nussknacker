package pl.touk.nussknacker.ui.process.migrate

import pl.touk.nussknacker.engine.api.{MetaData, StreamMetaData}
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.graph.evaluatedparam.{Parameter => NodeParameter}
import pl.touk.nussknacker.engine.graph.node
import pl.touk.nussknacker.engine.graph.node.{FragmentInput, FragmentInputDefinition, Processor, Source}
import pl.touk.nussknacker.engine.graph.service.ServiceRef
import pl.touk.nussknacker.engine.graph.source.SourceRef
import pl.touk.nussknacker.engine.migration.{NodeMigration, ProcessMigration, ProcessMigrations}
import pl.touk.nussknacker.ui.api.helpers.ProcessTestData

class TestMigrations(migrationsToAdd: Int*) extends ProcessMigrations {

  import pl.touk.nussknacker.engine.spel.Implicits._

  override def processMigrations: Map[Int, ProcessMigration] = Map(
    1 -> Migration1,
    2 -> Migration2,
    3 -> Migration3,
    4 -> Migration4,
    5 -> Migration5,
    6 -> Migration6,
    7 -> Migration7,
    8 -> Migration8,
    9 -> Migration9
  ).filter(m => migrationsToAdd.contains(m._1))

  object Migration1 extends NodeMigration {

    override val description = "testMigration1"

    override def migrateNode(metadata: MetaData): PartialFunction[node.NodeData, node.NodeData] = {
      case n @ Processor(_, ServiceRef(ProcessTestData.existingServiceId, parameters), _, _) =>
        n.copy(service = ServiceRef(ProcessTestData.otherExistingServiceId, parameters))
    }

  }

  object Migration2 extends ProcessMigration {

    override val description = "testMigration2"

    override def migrateProcess(canonicalProcess: CanonicalProcess, category: String): CanonicalProcess =
      canonicalProcess.copy(metaData =
        canonicalProcess.metaData.withTypeSpecificData(typeSpecificData = StreamMetaData(Some(11)))
      )

  }

  object Migration3 extends NodeMigration {

    override val description = "testMigration3"

    override def migrateNode(metadata: MetaData): PartialFunction[node.NodeData, node.NodeData] = {
      case n @ Processor(_, ServiceRef(ProcessTestData.existingServiceId, parameters), _, _) =>
        n.copy(service =
          ServiceRef(ProcessTestData.existingServiceId, NodeParameter("newParam", "'abc'") :: parameters)
        )
    }

  }

  object Migration4 extends NodeMigration {

    override val description = "testMigration4"

    override def migrateNode(metadata: MetaData): PartialFunction[node.NodeData, node.NodeData] = {
      case n @ Processor(_, ServiceRef(ProcessTestData.existingServiceId, parameters), _, _) =>
        n.copy(service =
          ServiceRef(ProcessTestData.existingServiceId, NodeParameter("newParam", "'abc'") :: parameters)
        )
    }

  }

  object Migration5 extends NodeMigration {

    override val description = "testMigration5"

    override def migrateNode(metadata: MetaData): PartialFunction[node.NodeData, node.NodeData] =
      throw new RuntimeException("made to fail..")
  }

  object Migration6 extends NodeMigration {

    override val description = "testMigration6"

    override def migrateNode(metadata: MetaData): PartialFunction[node.NodeData, node.NodeData] = {
      case n @ Processor(_, ServiceRef(ProcessTestData.existingServiceId, parameters), _, _) =>
        n.copy(service = ServiceRef(ProcessTestData.otherExistingServiceId, parameters))
    }

  }

  object Migration7 extends NodeMigration {

    override val description = "testMigration7"

    override def migrateNode(metadata: MetaData): PartialFunction[node.NodeData, node.NodeData] = {
      case sub @ FragmentInputDefinition(_, subParams, _)
          if !subParams.exists(_.name == "param42") && subParams.exists(_.name == "param1") =>
        sub.copy(parameters = sub.parameters.map(p => if (p.name == "param1") p.copy(name = "param42") else p))

      case sub @ FragmentInput(_, ref, _, _, _)
          if !ref.parameters.exists(_.name == "param42") && ref.parameters.exists(_.name == "param1") =>
        sub.copy(ref =
          sub.ref.copy(parameters =
            sub.ref.parameters.map(p => if (p.name == "param1") p.copy(name = "param42") else p)
          )
        )
    }

  }

  object Migration8 extends NodeMigration {

    override val description = "testMigration8"

    override def migrateNode(metadata: MetaData): PartialFunction[node.NodeData, node.NodeData] = {
      case n @ Source(_, ref @ SourceRef(ProcessTestData.existingSourceFactory, _), _) =>
        n.copy(ref = ref.copy(typ = ProcessTestData.otherExistingSourceFactory))
    }

  }

  object Migration9 extends NodeMigration {

    override val description = "testMigration9"

    override def migrateNode(metadata: MetaData): PartialFunction[node.NodeData, node.NodeData] = {
      case n @ Source(_, ref @ SourceRef(ProcessTestData.existingSourceFactory, parameters), _) =>
        n.copy(ref = ref.copy(parameters = NodeParameter("newParam", "'abc'") :: parameters))
    }

  }

}
