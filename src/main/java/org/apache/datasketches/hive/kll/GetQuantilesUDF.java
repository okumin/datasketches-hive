/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.datasketches.hive.kll;

import java.util.List;

import org.apache.datasketches.hive.common.BytesWritableHelper;
import org.apache.datasketches.kll.KllFloatsSketch;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.io.BytesWritable;

@Description(
  name = "GetQuantiles",
  value = "_FUNC_(sketch, fractions...)",
  extended = "Returns quantile values from a given KllFloatsSketch based on a given list of fractions."
  + " The fractions represent normalized ranks, and must be from 0 to 1 inclusive."
  + " For example, a fraction of 0.5 corresponds to 50th percentile,"
  + " which is the median value of the distribution (the number separating the higher"
  + " half of the probability distribution from the lower half).")
@SuppressWarnings("deprecation")
public class GetQuantilesUDF extends UDF {

  /**
   * Returns a list of quantile values from a given sketch
   * @param serializedSketch serialized sketch
   * @param fractions list of values from 0 to 1 inclusive
   * @return list of quantile values
   */
  public List<Float> evaluate(final BytesWritable serializedSketch, final Double... fractions) {
    if (serializedSketch == null) { return null; }
    final KllFloatsSketch sketch =
        KllFloatsSketch.heapify(BytesWritableHelper.wrapAsMemory(serializedSketch));
    return Util.primitivesToList(sketch.getQuantiles(Util.objectsToPrimitives(fractions)));
  }

}
