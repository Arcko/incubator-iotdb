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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.engine.memtable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.modification.Deletion;
import org.apache.iotdb.db.engine.modification.Modification;
import org.apache.iotdb.db.engine.querycontext.ReadOnlyMemChunk;
import org.apache.iotdb.db.exception.WriteProcessException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertPlan;
import org.apache.iotdb.db.rescon.TVListAllocator;
import org.apache.iotdb.db.utils.MemUtils;
import org.apache.iotdb.db.utils.datastructure.TVList;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;

public abstract class AbstractMemTable implements IMemTable {

  private final Map<String, Map<String, IWritableMemChunk>> memTableMap;

  private long version = Long.MAX_VALUE;

  private List<Modification> modifications = new ArrayList<>();

  private int avgSeriesPointNumThreshold = IoTDBDescriptor.getInstance().getConfig()
      .getAvgSeriesPointNumberThreshold();

  private long memSize = 0;

  private int seriesNumber = 0;

  private long totalPointsNum = 0;

  private long totalPointsNumThreshold = 0;

  public AbstractMemTable() {
    this.memTableMap = new HashMap<>();
  }

  public AbstractMemTable(Map<String, Map<String, IWritableMemChunk>> memTableMap) {
    this.memTableMap = memTableMap;
  }

  @Override
  public Map<String, Map<String, IWritableMemChunk>> getMemTableMap() {
    return memTableMap;
  }

  /**
   * check whether the given seriesPath is within this memtable.
   *
   * @return true if seriesPath is within this memtable
   */
  private boolean checkPath(String deviceId, String measurement) {
    return memTableMap.containsKey(deviceId) && memTableMap.get(deviceId).containsKey(measurement);
  }

  private IWritableMemChunk createIfNotExistAndGet(String deviceId, String measurement,
      MeasurementSchema schema) {
    if (!memTableMap.containsKey(deviceId)) {
      memTableMap.put(deviceId, new HashMap<>());
    }
    Map<String, IWritableMemChunk> memSeries = memTableMap.get(deviceId);
    if (!memSeries.containsKey(measurement)) {
      memSeries.put(measurement, genMemSeries(schema));
      seriesNumber++;
      totalPointsNumThreshold += avgSeriesPointNumThreshold;
    }
    return memSeries.get(measurement);
  }

  protected abstract IWritableMemChunk genMemSeries(MeasurementSchema schema);

  @Override
  public void insert(InsertPlan insertPlan) {
    for (int i = 0; i < insertPlan.getValues().length; i++) {

      if (insertPlan.getValues()[i] == null) {
        continue;
      }

      Object value = insertPlan.getValues()[i];
      memSize += MemUtils.getRecordSize(insertPlan.getSchemas()[i].getType(), value);

      write(insertPlan.getDeviceId(), insertPlan.getMeasurements()[i],
          insertPlan.getSchemas()[i], insertPlan.getTime(), value);
    }

    totalPointsNum += insertPlan.getValues().length;
  }

  @Override
  public void insertTablet(InsertTabletPlan insertTabletPlan, int start, int end)
      throws WriteProcessException {
    try {
      write(insertTabletPlan, start, end);
      memSize += MemUtils.getRecordSize(insertTabletPlan, start, end);
      totalPointsNum += insertTabletPlan.getMeasurements().length * (end - start);
    } catch (RuntimeException e) {
      throw new WriteProcessException(e.getMessage());
    }
  }


  @Override
  public void write(String deviceId, String measurement, MeasurementSchema schema, long insertTime,
      Object objectValue) {
    IWritableMemChunk memSeries = createIfNotExistAndGet(deviceId, measurement, schema);
    memSeries.write(insertTime, objectValue);
  }

  @Override
  public void write(InsertTabletPlan insertTabletPlan, int start, int end) {
    for (int i = 0; i < insertTabletPlan.getMeasurements().length; i++) {
      IWritableMemChunk memSeries = createIfNotExistAndGet(insertTabletPlan.getDeviceId(),
          insertTabletPlan.getMeasurements()[i], insertTabletPlan.getSchemas()[i]);
      memSeries.write(insertTabletPlan.getTimes(), insertTabletPlan.getColumns()[i],
          insertTabletPlan.getDataTypes()[i], start, end);
    }
  }


  public int getSeriesNumber() {
    return seriesNumber;
  }

  public long getTotalPointsNum() {
    return totalPointsNum;
  }

  @Override
  public long size() {
    long sum = 0;
    for (Map<String, IWritableMemChunk> seriesMap : memTableMap.values()) {
      for (IWritableMemChunk writableMemChunk : seriesMap.values()) {
        sum += writableMemChunk.count();
      }
    }
    return sum;
  }

  @Override
  public long memSize() {
    return memSize;
  }

  @Override
  public boolean reachTotalPointNumThreshold() {
    return totalPointsNum >= totalPointsNumThreshold;
  }

  @Override
  public void clear() {
    memTableMap.clear();
    modifications.clear();
    memSize = 0;
    seriesNumber = 0;
    totalPointsNum = 0;
    totalPointsNumThreshold = 0;
  }

  @Override
  public boolean isEmpty() {
    return memTableMap.isEmpty();
  }

  @Override
  public ReadOnlyMemChunk query(String deviceId, String measurement, TSDataType dataType,
      TSEncoding encoding, Map<String, String> props, long timeLowerBound)
      throws IOException, QueryProcessException {
    if (!checkPath(deviceId, measurement)) {
      return null;
    }
    long undeletedTime = findUndeletedTime(deviceId, measurement, timeLowerBound);
    IWritableMemChunk memChunk = memTableMap.get(deviceId).get(measurement);
    TVList chunkCopy = memChunk.getTVList().clone();

    chunkCopy.setTimeOffset(undeletedTime);
    return new ReadOnlyMemChunk(measurement, dataType, encoding, chunkCopy, props, getVersion());
  }


  private long findUndeletedTime(String deviceId, String measurement, long timeLowerBound) {
    long undeletedTime = Long.MIN_VALUE;
    for (Modification modification : modifications) {
      if (modification instanceof Deletion) {
        Deletion deletion = (Deletion) modification;
        if (deletion.getDevice().equals(deviceId) && deletion.getMeasurement().equals(measurement)
            && deletion.getTimestamp() > undeletedTime) {
          undeletedTime = deletion.getTimestamp();
        }
      }
    }
    return Math.max(undeletedTime + 1, timeLowerBound);
  }

  @Override
  public void delete(String deviceId, String measurementId, long timestamp) {
    Map<String, IWritableMemChunk> deviceMap = memTableMap.get(deviceId);
    if (deviceMap != null) {
      IWritableMemChunk chunk = deviceMap.get(measurementId);
      if (chunk == null) {
        return;
      }
      int deletedPointsNumber = chunk.delete(timestamp);
      totalPointsNum -= deletedPointsNumber;
    }
  }

  @Override
  public void delete(Deletion deletion) {
    this.modifications.add(deletion);
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  @Override
  public void release() {
    for (Entry<String, Map<String, IWritableMemChunk>> entry : memTableMap.entrySet()) {
      for (Entry<String, IWritableMemChunk> subEntry : entry.getValue().entrySet()) {
        TVListAllocator.getInstance().release(subEntry.getValue().getTVList());
      }
    }
  }
}
