/*
 * Copyright 2016, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hive.tuple;

import static com.yahoo.sketches.Util.DEFAULT_NOMINAL_ENTRIES;

import java.util.Arrays;

import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.udf.generic.AbstractGenericUDAFResolver;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDAFEvaluator;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDAFParameterInfo;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;

import com.yahoo.sketches.tuple.SummaryFactory;
import com.yahoo.sketches.tuple.UpdatableSummary;

public abstract class DataToSketchUDAF extends AbstractGenericUDAFResolver {

  @Override
  public GenericUDAFEvaluator getEvaluator(final GenericUDAFParameterInfo info) throws SemanticException {
    final ObjectInspector[] inspectors = info.getParameterObjectInspectors();

    if (inspectors.length < 2) {
      throw new UDFArgumentException("Expected at least 2 arguments");
    }
    if (inspectors.length > 4) {
      throw new UDFArgumentException("Expected at most 4 arguments");
    }
    ObjectInspectorValidator.validateCategoryPrimitive(inspectors[0], 0);

    // No validation of the value inspector since it can be anything.
    // Override this method to validate if needed.

    // number of nominal entries
    if (inspectors.length > 2) {
      ObjectInspectorValidator.validateIntegralParameter(inspectors[2], 2);
    }

    // sampling probability
    if (inspectors.length > 3) {
      ObjectInspectorValidator.validateGivenPrimitiveCategory(inspectors[3], 3, PrimitiveCategory.FLOAT);
    }

    return createEvaluator();
  }

  /**
   * This is needed because a concrete UDAF is going to have its own concrete evaluator static inner class.
   * @return an instance of evaluator
   */
  public abstract GenericUDAFEvaluator createEvaluator();

  public static abstract class DataToSketchEvaluator<U, S extends UpdatableSummary<U>> extends SketchEvaluator<S> {

    private static final float DEFAULT_SAMPLING_PROBABILITY = 1f;

    private PrimitiveObjectInspector keyInspector_;
    private PrimitiveObjectInspector valueInspector_;
    private PrimitiveObjectInspector samplingProbabilityInspector_;

    private Mode mode_;

    public DataToSketchEvaluator(SummaryFactory<S> summaryFactory) {
      super(summaryFactory);
    }

    @Override
    public ObjectInspector init(final Mode mode, final ObjectInspector[] inspectors) throws HiveException {
      super.init(mode, inspectors);
      mode_ = mode;
      if (mode == Mode.PARTIAL1 || mode == Mode.COMPLETE) {
        // input is original data
        keyInspector_ = (PrimitiveObjectInspector) inspectors[0];
        valueInspector_ = (PrimitiveObjectInspector) inspectors[1];
        if (inspectors.length > 2) {
          numNominalEntriesInspector_ = (PrimitiveObjectInspector) inspectors[2];
        }
        if (inspectors.length > 3) {
          samplingProbabilityInspector_ = (PrimitiveObjectInspector) inspectors[3];
        }
      } else {
        // input for PARTIAL2 and FINAL is the output from PARTIAL1
        intermediateInspector_ = (StructObjectInspector) inspectors[0];
      }

      if (mode == Mode.PARTIAL1 || mode == Mode.PARTIAL2) {
        // intermediate results need to include the the nominal number of entries
        return ObjectInspectorFactory.getStandardStructObjectInspector(
          Arrays.asList(NUM_NOMINAL_ENTRIES_FIELD, SKETCH_FIELD),
          Arrays.asList(
            PrimitiveObjectInspectorFactory.getPrimitiveWritableObjectInspector(PrimitiveCategory.INT),
            PrimitiveObjectInspectorFactory.getPrimitiveWritableObjectInspector(PrimitiveCategory.BINARY)
          )
        );
      } else {
        // final results include just the sketch
        return PrimitiveObjectInspectorFactory.getPrimitiveWritableObjectInspector(PrimitiveCategory.BINARY);
      }
    }

    @Override
    public void iterate(final @SuppressWarnings("deprecation") AggregationBuffer buf, final Object[] data) throws HiveException {
      if (data[0] == null) return;
      @SuppressWarnings("unchecked")
      final SketchState<U, S> state = (SketchState<U, S>) buf;
      if (!state.isInitialized()) {
        initializeState(state, data);
      }
      state.update(data[0], keyInspector_, extractValue(data[1], valueInspector_));
    }

    private void initializeState(final SketchState<U, S> state, final Object[] data) {
      int numNominalEntries = DEFAULT_NOMINAL_ENTRIES;
      if (numNominalEntriesInspector_ != null) {
        numNominalEntries = PrimitiveObjectInspectorUtils.getInt(data[2], numNominalEntriesInspector_);
      } 
      float samplingProbability = DEFAULT_SAMPLING_PROBABILITY;
      if (samplingProbabilityInspector_ != null) {
        samplingProbability = PrimitiveObjectInspectorUtils.getFloat(data[3],
            samplingProbabilityInspector_);
      }
      state.init(numNominalEntries, samplingProbability, summaryFactory_);
    }

    @SuppressWarnings("deprecation")
    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      if (mode_ == Mode.PARTIAL1 || mode_ == Mode.COMPLETE) {
        return new SketchState<U, S>();
      }
      return new UnionState<S>();
    }

    /**
     * Override this if it takes more than a cast to convert Hive value into the sketch update type U
     * @param data Hive value object
     * @param valueInspector PrimitiveObjectInspector for the value
     * @return extracted value
     * @throws HiveException if anything goes wrong
     */
    public U extractValue(final Object data, final PrimitiveObjectInspector valueInspector) throws HiveException {
      @SuppressWarnings("unchecked")
      final U value = (U) valueInspector.getPrimitiveJavaObject(data);
      return value;
    }

  }

}
