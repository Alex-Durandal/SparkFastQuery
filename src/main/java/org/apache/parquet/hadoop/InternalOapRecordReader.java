/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.parquet.hadoop;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.hadoop.api.InitContext;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.metadata.FileMetaData;
import org.apache.parquet.hadoop.metadata.IndexedParquetMetadata;
import org.apache.parquet.io.*;
import org.apache.parquet.io.api.RecordMaterializer;
import org.apache.parquet.it.unimi.dsi.fastutil.ints.IntList;
import org.apache.parquet.schema.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

import static java.lang.String.format;
import static org.apache.parquet.Log.DEBUG;
import static org.apache.parquet.hadoop.ParquetInputFormat.STRICT_TYPE_CHECKING;

public class InternalOapRecordReader<T> {

    private static final Logger LOG = LoggerFactory.getLogger(InternalOapRecordReader.class);

    private ColumnIOFactory columnIOFactory;

    private MessageType requestedSchema;
    private MessageType fileSchema;
    private int columnCount;
    private final ReadSupport<T> readSupport;

    private RecordMaterializer<T> recordConverter;

    private T currentValue;
    private long total;
    private long current = 0;
    private int currentBlock = -1;

    private boolean strictTypeChecking;

    private long totalCountLoadedSoFar = 0;

    private ParquetFileReader reader;

    private RecordReader<T> recordReader;

    private Iterator<IntList> rowIdsIter;

    private String createdBy;

    private ParquetReadMetrics metrics;

    /**
     * @param readSupport Object which helps reads files of the given type, e.g. Thrift, Avro.
     */
    InternalOapRecordReader(ReadSupport<T> readSupport) {
        this.readSupport = readSupport;
    }

    private void checkRead() throws IOException {
        if (current == totalCountLoadedSoFar) {
            if (current != 0) {
                metrics.recordMetrics(totalCountLoadedSoFar,columnCount);
            }

            LOG.info("at row " + current + ". reading next block");
            metrics.startReadOneRowGroup();
            PageReadStore pages = reader.readNextRowGroup();
            checkIOState(pages);
            metrics.overReadOneRowGroup(pages);
            if (LOG.isDebugEnabled()) {
                LOG.debug("initializing Record assembly with requested schema {}", requestedSchema);
            }
            MessageColumnIO columnIO =
                    columnIOFactory.getColumnIO(requestedSchema, fileSchema, strictTypeChecking);
            IntList rowIdList = rowIdsIter.next();
            this.recordReader = getRecordReader(columnIO,pages,rowIdList);
            metrics.startRecordAssemblyTime();
            totalCountLoadedSoFar += rowIdList.size();
            ++currentBlock;
        }
    }

    private RecordReader<T> getRecordReader(MessageColumnIO columnIO, PageReadStore pages, IntList rowIdList) {
        return RecordReaderFactory.getRecordReader(columnIO, pages, recordConverter, createdBy, rowIdList);
    }

    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }

    public void initialize(ParquetFileReader parquetFileReader, Configuration configuration)
            throws IOException {
        this.reader = parquetFileReader;
        FileMetaData parquetFileMetadata = parquetFileReader.getFooter().getFileMetaData();
        this.fileSchema = parquetFileMetadata.getSchema();
        Map<String, String> fileMetadata = parquetFileMetadata.getKeyValueMetaData();
        ReadSupport.ReadContext readContext = readSupport.init(new InitContext(
                configuration, toSetMultiMap(fileMetadata), fileSchema));
        this.createdBy = parquetFileMetadata.getCreatedBy();
        this.columnIOFactory = new ColumnIOFactory(createdBy);
        this.requestedSchema = readContext.getRequestedSchema();
        this.columnCount = requestedSchema.getPaths().size();
        this.recordConverter = readSupport.prepareForRead(
                configuration, fileMetadata, fileSchema, readContext);
        this.strictTypeChecking = configuration.getBoolean(STRICT_TYPE_CHECKING, true);
        List<IntList> rowIdsList = ((IndexedParquetMetadata)parquetFileReader.getFooter()).getRowIdsList();
        this.rowIdsIter = rowIdsList.iterator();
        for (IntList rowIdList : rowIdsList) {
            total += rowIdList.size();
        }
        this.reader.setRequestedSchema(requestedSchema);
        this.metrics = new ParquetReadMetrics();
        LOG.info("RecordReader initialized will read a total of {} records.", total);
    }


    boolean nextKeyValue() throws IOException, InterruptedException {
        boolean recordFound = false;

        while (!recordFound) {
            // no more records left
            if (current >= total) {
                return false;
            }

            try {
                checkRead();
                this.currentValue = recordReader.read();
                current++;
                if (recordReader.shouldSkipCurrentRecord()) {
                    // this record is being filtered via the filter2 package
                    if (DEBUG) {
                        LOG.debug("skipping record");
                    }
                    continue;
                }

                recordFound = true;

                if (DEBUG) {
                    LOG.debug("read value: " + currentValue);
                }
            } catch (RuntimeException e) {
                throw new ParquetDecodingException(format("Can not read value at %d in block %d in file %s",
                        current, currentBlock, reader.getPath()), e);
            }
        }
        return true;
    }

    T getCurrentValue() throws IOException, InterruptedException {
        return currentValue;
    }

    public float getProgress() throws IOException, InterruptedException {
        if(total == 0L){
            return 1F;
        }
        return (float) current / total;
    }

    private static <K, V> Map<K, Set<V>> toSetMultiMap(Map<K, V> map) {
        Map<K, Set<V>> setMultiMap = new HashMap<>();
        for (Map.Entry<K, V> entry : map.entrySet()) {
            Set<V> set = new HashSet<>();
            set.add(entry.getValue());
            setMultiMap.put(entry.getKey(), Collections.unmodifiableSet(set));
        }
        return Collections.unmodifiableMap(setMultiMap);
    }

    private void checkIOState(PageReadStore pages) throws IOException {
        if (pages == null) {
            throw new IOException(
                    "expecting more rows but reached last block. Read " + current + " out of " + total);
        }
    }

}
