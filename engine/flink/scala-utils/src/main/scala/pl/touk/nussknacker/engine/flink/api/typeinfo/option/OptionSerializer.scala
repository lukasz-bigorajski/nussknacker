/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.touk.nussknacker.engine.flink.api.typeinfo.option

import org.apache.flink.annotation.Internal
import org.apache.flink.api.common.typeutils._
import org.apache.flink.core.memory.{DataInputView, DataOutputView}

/** Serializer for [[Option]]. */
@Internal
@SerialVersionUID(-8635243274072627338L)
class OptionSerializer[A](val elemSerializer: TypeSerializer[A]) extends TypeSerializer[Option[A]] {

  override def duplicate: OptionSerializer[A] = {
    val duplicatedElemSerializer = elemSerializer.duplicate()

    if (duplicatedElemSerializer.eq(elemSerializer)) {
      this
    } else {
      new OptionSerializer[A](duplicatedElemSerializer)
    }
  }

  override def createInstance: Option[A] = {
    None
  }

  override def isImmutableType: Boolean = elemSerializer == null || elemSerializer.isImmutableType

  override def getLength: Int = -1

  override def copy(from: Option[A]): Option[A] = from match {
    case Some(a) => Some(elemSerializer.copy(a))
    case None    => from
  }

  override def copy(from: Option[A], reuse: Option[A]): Option[A] = copy(from)

  override def copy(source: DataInputView, target: DataOutputView): Unit = {
    val isSome = source.readBoolean()
    target.writeBoolean(isSome)
    if (isSome) {
      elemSerializer.copy(source, target)
    }
  }

  override def serialize(either: Option[A], target: DataOutputView): Unit = either match {
    case Some(a) =>
      target.writeBoolean(true)
      elemSerializer.serialize(a, target)
    case None =>
      target.writeBoolean(false)
  }

  override def deserialize(source: DataInputView): Option[A] = {
    val isSome = source.readBoolean()
    if (isSome) {
      Some(elemSerializer.deserialize(source))
    } else {
      None
    }
  }

  override def deserialize(reuse: Option[A], source: DataInputView): Option[A] = deserialize(source)

  override def equals(obj: Any): Boolean = {
    obj match {
      case optionSerializer: OptionSerializer[_] =>
        elemSerializer.equals(optionSerializer.elemSerializer)
      case _ => false
    }
  }

  override def hashCode(): Int = {
    elemSerializer.hashCode()
  }

  // --------------------------------------------------------------------------------------------
  // Serializer configuration snapshotting & compatibility
  // --------------------------------------------------------------------------------------------

  override def snapshotConfiguration(): TypeSerializerSnapshot[Option[A]] = {
    new ScalaOptionSerializerSnapshot[A](this)
  }

}

object OptionSerializer {

  /**
   * We need to keep this to be compatible with snapshots taken in Flink 1.3.0. Once Flink 1.3.x is
   * no longer supported, this can be removed.
   */
  class OptionSerializerConfigSnapshot[A]() extends CompositeTypeSerializerConfigSnapshot[Option[A]] {

    override def getVersion: Int = OptionSerializerConfigSnapshot.VERSION

    override def resolveSchemaCompatibility(
        newSerializer: TypeSerializer[Option[A]]
    ): TypeSerializerSchemaCompatibility[Option[A]] = {
      CompositeTypeSerializerUtil.delegateCompatibilityCheckToNewSnapshot(
        newSerializer,
        new ScalaOptionSerializerSnapshot[A](),
        getSingleNestedSerializerAndConfig.f1
      )
    }

  }

  object OptionSerializerConfigSnapshot {
    val VERSION = 1
  }

}
