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

package org.apache.druid.data.input.parquet;

import org.apache.druid.data.input.InputEntity;
import org.apache.druid.data.input.InputEntity.CleanableFile;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.IntermediateRowParsingReader;
import org.apache.druid.data.input.impl.MapInputRowParser;
import org.apache.druid.data.input.parquet.simple.ParquetGroupFlattenerMaker;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.java.util.common.parsers.JSONPathSpec;
import org.apache.druid.java.util.common.parsers.ObjectFlattener;
import org.apache.druid.java.util.common.parsers.ObjectFlatteners;
import org.apache.druid.java.util.common.parsers.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

public class ParquetReader extends IntermediateRowParsingReader<Group>
{
  private final Configuration conf;
  private final InputRowSchema inputRowSchema;
  private final InputEntity source;
  private final File temporaryDirectory;
  private final ObjectFlattener<Group> flattener;

  private final boolean projectPushdown;

  ParquetReader(
      Configuration conf,
      InputRowSchema inputRowSchema,
      InputEntity source,
      File temporaryDirectory,
      @Nullable JSONPathSpec flattenSpec,
      boolean binaryAsString,
      boolean prjectPushdown
  )
  {
    this.conf = conf;
    this.inputRowSchema = inputRowSchema;
    this.source = source;
    this.projectPushdown = prjectPushdown;
    this.temporaryDirectory = temporaryDirectory;
    this.flattener = ObjectFlatteners.create(
        flattenSpec,
        new ParquetGroupFlattenerMaker(
            binaryAsString,
            inputRowSchema.getDimensionsSpec().useSchemaDiscovery()
        )
    );
  }

  @Override
  protected CloseableIterator<Group> intermediateRowIterator() throws IOException
  {
    final Closer closer = Closer.create();
    byte[] buffer = new byte[InputEntity.DEFAULT_FETCH_BUFFER_SIZE];
    final ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
    final org.apache.parquet.hadoop.ParquetReader<Group> reader;
    try {

      org.apache.parquet.hadoop.ParquetReader.Builder<Group> builder;
      if (source.isSeekable() && projectPushdown) {
        // If projection pushdown is enabled and source supports it, use GroupReadSupport with projection pushdown
        GroupReadSupport readSupport = getGroupReadSupportWithProjectionPushdown();
        ParquetInputFile file = new ParquetInputFile(source);
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        builder = new org.apache.parquet.hadoop.ParquetReader.Builder<Group>(file)
        {
          @Override
          protected GroupReadSupport getReadSupport() {
            return readSupport;
          }

        };

      } else {
        final CleanableFile file = closer.register(source.fetch(temporaryDirectory, buffer));
        final Path path = new Path(file.file().toURI());

        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        builder = org.apache.parquet.hadoop.ParquetReader.builder(new GroupReadSupport(), path);

      }
      reader = closer.register(builder.withConf(conf).build());

    }
    catch (Exception e) {
      // We don't expect to see any exceptions thrown in the above try clause,
      // but we catch it just in case to avoid any potential resource leak.
      closer.close();
      throw new RuntimeException(e);
    }
    finally {
      Thread.currentThread().setContextClassLoader(currentClassLoader);
    }

    return new CloseableIterator<Group>()
    {
      Group value = null;

      @Override
      public boolean hasNext()
      {
        if (value == null) {
          try {
            value = reader.read();
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
        return value != null;
      }

      @Override
      public Group next()
      {
        if (value == null) {
          throw new NoSuchElementException();
        }
        Group currentValue = value;
        value = null;
        return currentValue;
      }

      @Override
      public void close() throws IOException
      {
        closer.close();
      }
    };
  }

  private GroupReadSupport getGroupReadSupportWithProjectionPushdown()
  {
    List<String> fields = inputRowSchema.getDimensionsSpec().getDimensionNames();
    return new GroupReadSupport() {
      @Override
      public ReadContext init(
          Configuration configuration, Map<String, String> keyValueMetaData,
          MessageType fileSchema) {
        List<Type> filteredFields = fileSchema.getFields().stream().filter(f -> fields.contains(f.getName())).collect(Collectors.toList());
        MessageType filteredSchema = new MessageType(fileSchema.getName(), filteredFields);
        MessageType requestedProjection = getSchemaForRead(fileSchema, filteredSchema);
        return new ReadContext(requestedProjection);
      }
    };
  }

  @Override
  protected InputEntity source()
  {
    return source;
  }

  @Override
  protected List<InputRow> parseInputRows(Group intermediateRow) throws ParseException
  {
    return Collections.singletonList(
        MapInputRowParser.parse(
            inputRowSchema,
            flattener.flatten(intermediateRow)
        )
    );
  }

  @Override
  protected List<Map<String, Object>> toMap(Group intermediateRow)
  {
    return Collections.singletonList(flattener.toMap(intermediateRow));
  }
}
