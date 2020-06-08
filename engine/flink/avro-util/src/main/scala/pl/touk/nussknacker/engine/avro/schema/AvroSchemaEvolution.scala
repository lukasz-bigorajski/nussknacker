package pl.touk.nussknacker.engine.avro.schema

import org.apache.avro.Schema
import org.apache.avro.generic.GenericContainer

trait AvroSchemaEvolution {
  def alignRecordToSchema(container: GenericContainer, schema: Schema): GenericContainer
}

class AvroSchemaEvolutionException(message: String, cause: Throwable) extends RuntimeException(message, cause)
