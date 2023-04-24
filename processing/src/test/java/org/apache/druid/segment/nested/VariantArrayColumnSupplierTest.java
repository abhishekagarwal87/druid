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

package org.apache.druid.segment.nested;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.druid.guice.NestedDataModule;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.io.smoosh.FileSmoosher;
import org.apache.druid.java.util.common.io.smoosh.SmooshedFileMapper;
import org.apache.druid.java.util.common.io.smoosh.SmooshedWriter;
import org.apache.druid.query.DefaultBitmapResultFactory;
import org.apache.druid.segment.AutoTypeColumnIndexer;
import org.apache.druid.segment.AutoTypeColumnMerger;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.IndexableAdapter;
import org.apache.druid.segment.SimpleAscendingOffset;
import org.apache.druid.segment.column.ColumnBuilder;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.DruidPredicateIndex;
import org.apache.druid.segment.column.NullValueIndex;
import org.apache.druid.segment.column.StringValueSetIndex;
import org.apache.druid.segment.data.BitmapSerdeFactory;
import org.apache.druid.segment.data.RoaringBitmapSerdeFactory;
import org.apache.druid.segment.writeout.SegmentWriteOutMediumFactory;
import org.apache.druid.segment.writeout.TmpFileSegmentWriteOutMediumFactory;
import org.apache.druid.testing.InitializedNullHandlingTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public class VariantArrayColumnSupplierTest extends InitializedNullHandlingTest
{

  @Rule
  public final TemporaryFolder tempFolder = new TemporaryFolder();

  BitmapSerdeFactory bitmapSerdeFactory = RoaringBitmapSerdeFactory.getInstance();
  DefaultBitmapResultFactory resultFactory = new DefaultBitmapResultFactory(bitmapSerdeFactory.getBitmapFactory());

  List<List<Long>> data = Arrays.asList(
      Collections.emptyList(),
      Arrays.asList(1L, null, 2L),
      null,
      Collections.singletonList(null),
      Arrays.asList(3L, 4L),
      Arrays.asList(null, null)
  );

  Closer closer = Closer.create();

  SmooshedFileMapper fileMapper;

  ByteBuffer baseBuffer;

  @BeforeClass
  public static void staticSetup()
  {
    NestedDataModule.registerHandlersAndSerde();
  }

  @Before
  public void setup() throws IOException
  {
    final String fileNameBase = "test";
    fileMapper = smooshify(fileNameBase, tempFolder.newFolder(), data);
    baseBuffer = fileMapper.mapFile(fileNameBase);
  }

  private SmooshedFileMapper smooshify(
      String fileNameBase,
      File tmpFile,
      List<?> data
  )
      throws IOException
  {
    SegmentWriteOutMediumFactory writeOutMediumFactory = TmpFileSegmentWriteOutMediumFactory.instance();
    try (final FileSmoosher smoosher = new FileSmoosher(tmpFile)) {
      VariantArrayColumnSerializer serializer = new VariantArrayColumnSerializer(
          fileNameBase,
          new IndexSpec(),
          writeOutMediumFactory.makeSegmentWriteOutMedium(tempFolder.newFolder()),
          closer
      );

      AutoTypeColumnIndexer indexer = new AutoTypeColumnIndexer();
      for (Object o : data) {
        indexer.processRowValsToUnsortedEncodedKeyComponent(o, false);
      }
      SortedMap<String, FieldTypeInfo.MutableTypeSet> sortedFields = new TreeMap<>();

      IndexableAdapter.NestedColumnMergable mergable = closer.register(
          new IndexableAdapter.NestedColumnMergable(indexer.getSortedValueLookups(), indexer.getFieldTypeInfo())
      );
      SortedValueDictionary globalDictionarySortedCollector = mergable.getValueDictionary();
      mergable.mergeFieldsInto(sortedFields);

      serializer.openDictionaryWriter();
      serializer.serializeDictionaries(
          globalDictionarySortedCollector.getSortedStrings(),
          globalDictionarySortedCollector.getSortedLongs(),
          globalDictionarySortedCollector.getSortedDoubles(),
          () -> new AutoTypeColumnMerger.ArrayDictionaryMergingIterator(
              new Iterable[]{globalDictionarySortedCollector.getSortedArrays()},
              serializer.getGlobalLookup()
          )
      );
      serializer.open();

      NestedDataColumnSupplierTest.SettableSelector valueSelector = new NestedDataColumnSupplierTest.SettableSelector();
      for (Object o : data) {
        valueSelector.setObject(StructuredData.wrap(o));
        serializer.serialize(valueSelector);
      }

      try (SmooshedWriter writer = smoosher.addWithSmooshedWriter(fileNameBase, serializer.getSerializedSize())) {
        serializer.writeTo(writer, smoosher);
      }
      smoosher.close();
      return closer.register(SmooshedFileMapper.load(tmpFile));
    }
  }

  @After
  public void teardown() throws IOException
  {
    closer.close();
  }

  @Test
  public void testBasicFunctionality() throws IOException
  {
    ColumnBuilder bob = new ColumnBuilder();
    bob.setFileMapper(fileMapper);
    VariantArrayColumnAndIndexSupplier supplier = VariantArrayColumnAndIndexSupplier.read(
        ColumnType.LONG_ARRAY,
        ByteOrder.nativeOrder(),
        bitmapSerdeFactory,
        baseBuffer,
        bob,
        NestedFieldColumnIndexSupplierTest.ALWAYS_USE_INDEXES
    );
    try (VariantArrayColumn column = (VariantArrayColumn) supplier.get()) {
      smokeTest(supplier, column);
    }
  }

  @Test
  public void testConcurrency() throws ExecutionException, InterruptedException
  {
    // if this test ever starts being to be a flake, there might be thread safety issues
    ColumnBuilder bob = new ColumnBuilder();
    bob.setFileMapper(fileMapper);
    VariantArrayColumnAndIndexSupplier supplier = VariantArrayColumnAndIndexSupplier.read(
        ColumnType.LONG_ARRAY,
        ByteOrder.nativeOrder(),
        bitmapSerdeFactory,
        baseBuffer,
        bob,
        NestedFieldColumnIndexSupplierTest.ALWAYS_USE_INDEXES
    );
    final String expectedReason = "none";
    final AtomicReference<String> failureReason = new AtomicReference<>(expectedReason);

    final int threads = 10;
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
        Execs.multiThreaded(threads, "StandardNestedColumnSupplierTest-%d")
    );
    Collection<ListenableFuture<?>> futures = new ArrayList<>(threads);
    final CountDownLatch threadsStartLatch = new CountDownLatch(1);
    for (int i = 0; i < threads; ++i) {
      futures.add(
          executorService.submit(() -> {
            try {
              threadsStartLatch.await();
              for (int iter = 0; iter < 5000; iter++) {
                try (VariantArrayColumn column = (VariantArrayColumn) supplier.get()) {
                  smokeTest(supplier, column);
                }
              }
            }
            catch (Throwable ex) {
              failureReason.set(ex.getMessage());
            }
          })
      );
    }
    threadsStartLatch.countDown();
    Futures.allAsList(futures).get();
    Assert.assertEquals(expectedReason, failureReason.get());
  }

  private void smokeTest(VariantArrayColumnAndIndexSupplier supplier, VariantArrayColumn column)
  {
    SimpleAscendingOffset offset = new SimpleAscendingOffset(data.size());
    ColumnValueSelector<?> valueSelector = column.makeColumnValueSelector(offset);

    StringValueSetIndex valueSetIndex = supplier.as(StringValueSetIndex.class);
    Assert.assertNull(valueSetIndex);
    DruidPredicateIndex predicateIndex = supplier.as(DruidPredicateIndex.class);
    Assert.assertNull(predicateIndex);
    NullValueIndex nullValueIndex = supplier.as(NullValueIndex.class);
    Assert.assertNotNull(nullValueIndex);

    SortedMap<String, FieldTypeInfo.MutableTypeSet> fields = column.getFieldTypeInfo();
    Assert.assertEquals(
        ImmutableMap.of(NestedPathFinder.JSON_PATH_ROOT, new FieldTypeInfo.MutableTypeSet().add(ColumnType.LONG_ARRAY)),
        fields
    );

    for (int i = 0; i < data.size(); i++) {
      List<Long> row = data.get(i);

      // in default value mode, even though the input row had an empty string, the selector spits out null, so we want
      // to take the null checking path

      if (row != null) {
        Assert.assertArrayEquals(row.toArray(), (Object[]) valueSelector.getObject());
        Assert.assertFalse(nullValueIndex.forNull().computeBitmapResult(resultFactory).get(i));

      } else {
        Assert.assertNull(valueSelector.getObject());
        Assert.assertTrue(nullValueIndex.forNull().computeBitmapResult(resultFactory).get(i));
      }

      offset.increment();
    }
  }
}
