/**
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

package org.apache.hadoop.hive.ql.optimizer.spark;

import java.util.Stack;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.ql.exec.FileSinkOperator;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.ReduceSinkOperator;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.spark.SparkUtilities;
import org.apache.hadoop.hive.ql.exec.spark.session.SparkSession;
import org.apache.hadoop.hive.ql.exec.spark.session.SparkSessionManager;
import org.apache.hadoop.hive.ql.exec.spark.session.SparkSessionManagerImpl;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.lib.NodeProcessor;
import org.apache.hadoop.hive.ql.lib.NodeProcessorCtx;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.parse.spark.GenSparkUtils;
import org.apache.hadoop.hive.ql.parse.spark.OptimizeSparkProcContext;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.ReduceSinkDesc;

import scala.Tuple2;

/**
 * SetSparkReducerParallelism determines how many reducers should
 * be run for a given reduce sink, clone from SetReducerParallelism.
 */
public class SetSparkReducerParallelism implements NodeProcessor {

  private static final Log LOG = LogFactory.getLog(SetSparkReducerParallelism.class.getName());

  // Spark memory per task, and total number of cores
  private Tuple2<Long, Integer> sparkMemoryAndCores;

  @Override
  public Object process(Node nd, Stack<Node> stack,
      NodeProcessorCtx procContext, Object... nodeOutputs)
      throws SemanticException {

    OptimizeSparkProcContext context = (OptimizeSparkProcContext) procContext;

    ReduceSinkOperator sink = (ReduceSinkOperator) nd;
    ReduceSinkDesc desc = sink.getConf();

    int maxReducers = context.getConf().getIntVar(HiveConf.ConfVars.MAXREDUCERS);
    int constantReducers = context.getConf().getIntVar(HiveConf.ConfVars.HADOOPNUMREDUCERS);

    if (context.getVisitedReduceSinks().contains(sink)) {
      // skip walking the children
      LOG.debug("Already processed reduce sink: " + sink.getName());
      return true;
    }

    context.getVisitedReduceSinks().add(sink);

    if (desc.getNumReducers() <= 0) {
      if (constantReducers > 0) {
        LOG.info("Parallelism for reduce sink " + sink + " set by user to " + constantReducers);
        desc.setNumReducers(constantReducers);
      } else {
        //If it's a FileSink to bucketed files, use the bucket count as the reducer number
        FileSinkOperator fso = GenSparkUtils.getChildOperator(sink, FileSinkOperator.class);
        if (fso != null) {
          String bucketCount = fso.getConf().getTableInfo().getProperties().getProperty(
            hive_metastoreConstants.BUCKET_COUNT);
          int numBuckets = bucketCount == null ? 0 : Integer.parseInt(bucketCount);
          if (numBuckets > 0) {
            LOG.info("Set parallelism for reduce sink " + sink + " to: " + numBuckets + " (buckets)");
            desc.setNumReducers(numBuckets);
            return false;
          }
        }
        long numberOfBytes = 0;

        // we need to add up all the estimates from the siblings of this reduce sink
        for (Operator<? extends OperatorDesc> sibling
          : sink.getChildOperators().get(0).getParentOperators()) {
          if (sibling.getStatistics() != null) {
            numberOfBytes += sibling.getStatistics().getDataSize();
            if (LOG.isDebugEnabled()) {
              LOG.debug("Sibling " + sibling + " has stats: " + sibling.getStatistics());
            }
          } else {
            LOG.warn("No stats available from: " + sibling);
          }
        }

        if (sparkMemoryAndCores == null) {
          SparkSessionManager sparkSessionManager = null;
          SparkSession sparkSession = null;
          try {
            sparkSessionManager = SparkSessionManagerImpl.getInstance();
            sparkSession = SparkUtilities.getSparkSession(
              context.getConf(), sparkSessionManager);
            sparkMemoryAndCores = sparkSession.getMemoryAndCores();
          } catch (Exception e) {
            throw new SemanticException("Failed to get spark memory/core info: " + e, e);
          } finally {
            if (sparkSession != null && sparkSessionManager != null) {
              try {
                sparkSessionManager.returnSession(sparkSession);
              } catch (HiveException ex) {
                LOG.error("Failed to return the session to SessionManager: " + ex, ex);
              }
            }
          }
        }

        // Divide it by 2 so that we can have more reducers
        long bytesPerReducer = sparkMemoryAndCores._1.longValue() / 2;
        int numReducers = Utilities.estimateReducers(numberOfBytes, bytesPerReducer,
          maxReducers, false);

        // If there are more cores, use the number of cores
        int cores = sparkMemoryAndCores._2.intValue();
        if (numReducers < cores) {
          numReducers = cores;
        }
        LOG.info("Set parallelism parameters: cores = " + cores + ", numReducers = " + numReducers
          + ", bytesPerReducer = " + bytesPerReducer + ", numberOfBytes = " + numberOfBytes);
        LOG.info("Set parallelism for reduce sink " + sink + " to: " + numReducers + " (calculated)");
        desc.setNumReducers(numReducers);
      }
    } else {
      LOG.info("Number of reducers determined to be: " + desc.getNumReducers());
    }

    return false;
  }

}