/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.error.DruidException;
import org.apache.druid.error.InvalidInput;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.nested.NestedCommonFormatColumn;
import org.apache.druid.segment.nested.NestedCommonFormatColumnSerializer;
import org.apache.druid.segment.nested.NestedDataColumnSerializer;
import org.apache.druid.segment.nested.ScalarDoubleColumnSerializer;
import org.apache.druid.segment.nested.ScalarLongColumnSerializer;
import org.apache.druid.segment.nested.ScalarStringColumnSerializer;
import org.apache.druid.segment.nested.StructuredData;
import org.apache.druid.segment.nested.VariantColumnSerializer;
import org.apache.druid.segment.serde.NestedCommonFormatColumnPartSerde;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Common {@link DimensionSchema} for ingestion of 'standard' Druid built-in {@link ColumnType} datatypes.
 *
 * Automatically determines the most appropriate type for the given input data, able to produce columns of type:
 *    {@link ColumnType#STRING}
 *    {@link ColumnType#STRING_ARRAY}
 *    {@link ColumnType#LONG}
 *    {@link ColumnType#LONG_ARRAY}
 *    {@link ColumnType#DOUBLE}
 *    {@link ColumnType#DOUBLE_ARRAY}
 *    {@link ColumnType#NESTED_DATA}
 *
 * and includes bitmap value set indexes. Input of mixed type will be stored as {@link ColumnType#NESTED_DATA}.
 *
 * @see AutoTypeColumnIndexer
 * @see AutoTypeColumnMerger
 * @see NestedCommonFormatColumnSerializer
 * @see VariantColumnSerializer
 * @see ScalarDoubleColumnSerializer
 * @see ScalarLongColumnSerializer
 * @see NestedDataColumnSerializer
 * @see ScalarStringColumnSerializer
 * @see NestedCommonFormatColumnPartSerde
 * @see NestedCommonFormatColumn
 */
public class AutoTypeColumnSchema extends DimensionSchema
{
  public static final String TYPE = "auto";
  public static final int VERSION_1 = 1;
  public static final int VERSION_2 = 2;

  @Nullable
  private final ColumnType castToType;

  private final int version;

  @VisibleForTesting
  public AutoTypeColumnSchema(String name, @Nullable ColumnType castToType)
  {
    this(name, castToType, null);
  }

  @JsonCreator
  public AutoTypeColumnSchema(
      @JsonProperty("name") String name,
      @JsonProperty("castToType") @Nullable ColumnType castToType,
      @JsonProperty("version") @Nullable Integer version
  )
  {
    super(name, null, true);
    // auto doesn't currently do FLOAT since expressions only support DOUBLE
    if (ColumnType.FLOAT.equals(castToType)) {
      this.castToType = ColumnType.DOUBLE;
    } else if (ColumnType.FLOAT_ARRAY.equals(castToType)) {
      this.castToType = ColumnType.DOUBLE_ARRAY;
    } else {
      this.castToType = castToType;
    }
    if (null == version) {
      this.version = VERSION_1;
    } else  {
      this.version = version;
    }

    if (this.version != VERSION_1 && this.version != VERSION_2) {
      throw InvalidInput.exception("Invalid version: [%s]", version);
    }
  }

  @Override
  public String getTypeName()
  {
    return TYPE;
  }

  @Override
  public ColumnType getColumnType()
  {
    return castToType != null ? castToType : ColumnType.NESTED_DATA;
  }

  @Nullable
  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public ColumnType getCastToType()
  {
    return castToType;
  }

  @JsonProperty
  public int getVersion()
  {
    return version;
  }

  @Override
  public DimensionHandler<StructuredData, StructuredData, StructuredData> getDimensionHandler()
  {
    return new NestedCommonFormatColumnHandler(getName(), castToType, version);
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    AutoTypeColumnSchema that = (AutoTypeColumnSchema) o;
    return Objects.equals(castToType, that.castToType) && version == that.version;
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(super.hashCode(), castToType, version);
  }

  @Override
  public String toString()
  {
    return "DimensionSchema{" +
           "name='" + getName() + '\'' +
           ", valueType=" + getColumnType() +
           ", typeName=" + getTypeName() +
           ", multiValueHandling=" + getMultiValueHandling() +
           ", createBitmapIndex=" + hasBitmapIndex() +
           ", castToType=" + castToType +
           ", version=" + version +
           '}';
  }
}
