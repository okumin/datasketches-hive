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

package org.apache.datasketches.hive.hll;

import java.util.Arrays;

import org.apache.datasketches.hll.TgtHllType;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.exec.UDFArgumentTypeException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.udf.generic.AbstractGenericUDAFResolver;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDAFEvaluator;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDAFParameterInfo;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;

/**
 * Hive UDAF to create an HllSketch from raw data.
 *
 * <p><b>Note</b> Strings as raw data values are encoded as a UTF-16 VARCHAR
 * prior to being submitted to the sketch. If the user requires a different
 * encoding for cross-platform compatibility, it is recommended that these values be encoded prior
 * to being submitted and then typed as a BINARY byte[].</p>
 */
@Description(
    name = "dataToSketch",
    value = "_FUNC_(expr, lgK, type) - "
        + "Compute a sketch on data 'expr' with given parameters lgK and target type",
    extended = "Example:\n"
    + "> SELECT dataToSketch(val, 12) FROM src;\n"
    + "The return value is a binary blob that can be operated on by other sketch related functions."
    + " The lgK parameter controls the sketch size and rlative error expected from the sketch."
    + " It is optional and must be from 4 to 21. The default is 12, which is expected to yield errors"
    + " of roughly +-3% in the estimation of uniques with 95% confidence."
    + " The target type parameter is optional and must be 'HLL_4', 'HLL_6' or 'HLL_8'."
    + " The default is 'HLL_4'")
@SuppressWarnings({"javadoc","deprecation"})
public class DataToSketchUDAF extends AbstractGenericUDAFResolver {

  /**
   * Performs argument number and type validation. DataToSketch expects
   * to receive between one and three arguments.
   * <ul>
   * <li>The first (required) is the value to add to the sketch and must be a primitive.</li>
   *
   * <li>The second (optional) is the lgK from 4 to 21 (default 12).
   * This must be an integral value and must be constant.</li>
   *
   * <li>The third (optional) is the target HLL type and must be a string 'HLL_4',
   * 'HLL_6' or 'HLL_8' (default 'HLL_4').</li>
   * </ul>
   *
   * @see org.apache.hadoop.hive.ql.udf.generic.AbstractGenericUDAFResolver
   * #getEvaluator(org.apache.hadoop.hive.ql.udf.generic.GenericUDAFParameterInfo)
   *
   * @param info Parameter info to validate
   * @return The GenericUDAFEvaluator that should be used to calculate the function.
   */
  @Override
  public GenericUDAFEvaluator getEvaluator(final GenericUDAFParameterInfo info) throws SemanticException {
    final ObjectInspector[] inspectors = info.getParameterObjectInspectors();

    // Validate the correct number of parameters
    if (inspectors.length < 1) {
      throw new UDFArgumentException("Please specify at least 1 argument");
    }

    if (inspectors.length > 3) {
      throw new UDFArgumentException("Please specify no more than 3 arguments");
    }

    // Validate first parameter type
    ObjectInspectorValidator.validateCategoryPrimitive(inspectors[0], 0);

    // Validate second argument if present
    if (inspectors.length > 1) {
      ObjectInspectorValidator.validateIntegralParameter(inspectors[1], 1);
      if (!ObjectInspectorUtils.isConstantObjectInspector(inspectors[1])) {
        throw new UDFArgumentTypeException(1, "The second argument must be a constant");
      }
    }

    // Validate third argument if present
    if (inspectors.length > 2) {
      ObjectInspectorValidator.validateGivenPrimitiveCategory(inspectors[2], 2, PrimitiveCategory.STRING);
      if (!ObjectInspectorUtils.isConstantObjectInspector(inspectors[2])) {
        throw new UDFArgumentTypeException(2, "The third argument must be a constant");
      }
    }

    return new DataToSketchEvaluator();
  }

  public static class DataToSketchEvaluator extends SketchEvaluator {

    private Mode mode_;

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      // Different State is used for the iterate phase and the merge phase.
      // A user reported that in some version of Hive this was apparently called before init,
      // so the mode_ was null. A solution was implemented to have UnionState, which can work
      // in both cases, but SketchState is more space-efficient.
      // HLL sketch is about compactness, so let's use SketchState if possible.
      if (this.mode_ == Mode.PARTIAL1 || this.mode_ == Mode.COMPLETE) { // iterate() will be used
        return new SketchState();
      }
      return new UnionState();
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.apache.hadoop.hive.ql.udf.generic.GenericUDAFEvaluator#init(org.apache
     * .hadoop.hive.ql.udf.generic.GenericUDAFEvaluator.Mode,
     * org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector[])
     */
    @Override
    public ObjectInspector init(final Mode mode, final ObjectInspector[] parameters) throws HiveException {
      super.init(mode, parameters);
      this.mode_ = mode;
      if (mode == Mode.PARTIAL1 || mode == Mode.COMPLETE) {
        // input is original data
        this.inputInspector_ = (PrimitiveObjectInspector) parameters[0];
        if (parameters.length > 1) {
          this.lgKInspector_ = (PrimitiveObjectInspector) parameters[1];
        }
        if (parameters.length > 2) {
          this.hllTypeInspector_ = (PrimitiveObjectInspector) parameters[2];
        }
      } else {
        // input for PARTIAL2 and FINAL is the output from PARTIAL1
        this.intermediateInspector_ = (StructObjectInspector) parameters[0];
      }

      if (mode == Mode.PARTIAL1 || mode == Mode.PARTIAL2) {
        // intermediate results need to include the lgK and the target HLL type
        return ObjectInspectorFactory.getStandardStructObjectInspector(
          Arrays.asList(LG_K_FIELD, HLL_TYPE_FIELD, SKETCH_FIELD),
          Arrays.asList(
            PrimitiveObjectInspectorFactory.getPrimitiveWritableObjectInspector(PrimitiveCategory.INT),
            PrimitiveObjectInspectorFactory.getPrimitiveWritableObjectInspector(PrimitiveCategory.STRING),
            PrimitiveObjectInspectorFactory.getPrimitiveWritableObjectInspector(PrimitiveCategory.BINARY)
          )
        );
      }
      // final results include just the sketch
      return PrimitiveObjectInspectorFactory.getPrimitiveWritableObjectInspector(PrimitiveCategory.BINARY);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.apache.hadoop.hive.ql.udf.generic.GenericUDAFEvaluator#iterate(org
     * .apache
     * .hadoop.hive.ql.udf.generic.GenericUDAFEvaluator.AggregationBuffer,
     * java.lang.Object[])
     */
    @Override
    public void iterate(final AggregationBuffer agg,
        final Object[] parameters) throws HiveException {
      if (parameters[0] == null) { return; }
      final State state = (State) agg;
      if (!state.isInitialized()) {
        initializeState(state, parameters);
      }
      state.update(parameters[0], this.inputInspector_);
    }

    private void initializeState(final State state, final Object[] parameters) {
      int lgK = DEFAULT_LG_K;
      if (this.lgKInspector_ != null) {
        lgK = PrimitiveObjectInspectorUtils.getInt(parameters[1], this.lgKInspector_);
      }
      TgtHllType type = DEFAULT_HLL_TYPE;
      if (this.hllTypeInspector_ != null) {
        type =
          TgtHllType.valueOf(PrimitiveObjectInspectorUtils.getString(parameters[2], this.hllTypeInspector_));
      }
      state.init(lgK, type);
    }

  }

}
