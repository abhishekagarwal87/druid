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

package org.apache.druid.segment.nested.auto.v2;

import org.apache.druid.segment.AutoTypeColumnIndexer;
import org.apache.druid.segment.AutoTypeColumnSchema;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.nested.StructuredData;
import org.apache.druid.segment.nested.StructuredDataProcessor;

import javax.annotation.Nullable;

public class AutoTypeColumnIndexerV2 extends AutoTypeColumnIndexer
{

  public AutoTypeColumnIndexerV2(String name, @Nullable ColumnType castToType)
  {
    super(name, castToType);
  }

  @Override
  protected StructuredData processRawValue(StructuredDataProcessor.ProcessResults info, @Nullable Object value)
  {
    if (null != info && info.hasObjects()) {
      globalDictionary.addStringArray(info.getLiteralFieldsAsPathArray());
    }
    return StructuredData.wrap(value);
  }

  @Override
  public int getVersion()
  {
    return AutoTypeColumnSchema.VERSION_2;
  }
}
