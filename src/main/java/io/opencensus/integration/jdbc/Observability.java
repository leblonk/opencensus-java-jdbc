// Copyright 2018, OpenCensus Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.orijtech.integrations.ocjdbc;

import io.opencensus.common.Scope;
import io.opencensus.stats.Aggregation;
import io.opencensus.stats.Aggregation.Distribution;
import io.opencensus.stats.BucketBoundaries;
import io.opencensus.stats.Measure.MeasureDouble;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.stats.View;
import io.opencensus.stats.View.Name;
import io.opencensus.stats.ViewManager;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagContextBuilder;
import io.opencensus.tags.TagKey;
import io.opencensus.tags.TagMetadata;
import io.opencensus.tags.TagMetadata.TagTtl;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.Span;
import io.opencensus.trace.Status;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import java.util.Arrays;
import java.util.EnumSet;
import javax.annotation.Nullable;

/** Observability for JDBC. */
public final class Observability {

  private Observability() {}

  private static final StatsRecorder statsRecorder = Stats.getStatsRecorder();
  private static final Tagger tagger = Tags.getTagger();
  private static final Tracer tracer = Tracing.getTracer();

  // Units of measurement
  private static final String MILLISECONDS = "ms";

  // Tag keys
  static final TagKey JAVA_SQL_METHOD = TagKey.create("java_sql_method");
  static final TagKey JAVA_SQL_ERROR = TagKey.create("java_sql_error");
  static final TagKey JAVA_SQL_STATUS = TagKey.create("java_sql_status");

  // Tag values
  // VisibleForTesting
  static final TagValue VALUE_OK = TagValue.create("OK");
  static final TagValue VALUE_ERROR = TagValue.create("ERROR");

  // Measures
  static final MeasureDouble MEASURE_LATENCY_MS =
      MeasureDouble.create(
          "java.sql/latency", "The latency of calls in milliseconds", MILLISECONDS);

  // VisibleForTesting
  static final Aggregation DEFAULT_MILLISECONDS_DISTRIBUTION =
      Distribution.create(
          BucketBoundaries.create(
              Arrays.asList(
                  // [0ms, 0.001ms, 0.005ms, 0.01ms, 0.05ms, 0.1ms, 0.5ms, 1ms, 1.5ms, 2ms, 2.5ms,
                  // 5ms, 10ms, 25ms, 50ms, 100ms, 200ms, 400ms, 600ms, 800ms, 1s, 1.5s, 2s, 2.5s,
                  // 5s, 10s, 20s, 40s, 100s, 200s, 500s]
                  0.0,
                  0.001,
                  0.005,
                  0.01,
                  0.05,
                  0.1,
                  0.5,
                  1.0,
                  1.5,
                  2.0,
                  2.5,
                  5.0,
                  10.0,
                  25.0,
                  50.0,
                  100.0,
                  200.0,
                  400.0,
                  600.0,
                  800.0,
                  1000.0,
                  1500.0,
                  2000.0,
                  2500.0,
                  5000.0,
                  10000.0,
                  20000.0,
                  40000.0,
                  100000.0,
                  200000.0,
                  500000.0)));

  static final Aggregation COUNT = Aggregation.Count.create();
  static final TagMetadata TAG_METADATA_TTL_UNLIMITED =
      TagMetadata.create(TagTtl.UNLIMITED_PROPAGATION);

  static final View SQL_CLIENT_LATENCY_VIEW =
      View.create(
          Name.create("java.sql/client/latency"),
          "The distribution of the latencies of various calls in milliseconds",
          MEASURE_LATENCY_MS,
          DEFAULT_MILLISECONDS_DISTRIBUTION,
          Arrays.asList(JAVA_SQL_METHOD, JAVA_SQL_ERROR, JAVA_SQL_STATUS));

  static final View SQL_CLIENT_CALLS_VIEW =
      View.create(
          Name.create("java.sql/client/calls"),
          "The number of various calls of methods",
          MEASURE_LATENCY_MS,
          COUNT,
          Arrays.asList(JAVA_SQL_METHOD, JAVA_SQL_ERROR, JAVA_SQL_STATUS));

  public enum TraceOption {
    NONE,
    ANNOTATE_TRACES_WITH_SQL
  }

  static boolean shouldAnnotateSpansWithSQL(EnumSet<TraceOption> opts) {
    for (TraceOption opt : opts) {
      if (opt == TraceOption.ANNOTATE_TRACES_WITH_SQL) {
        return true;
      }
    }

    return false;
  }

  // TrackingOperation records both the metric latency in milliseconds, and the span created by
  // tracing the calling function.
  static final class TrackingOperation {
    private final Span span;
    private final long startTimeNs;
    private final String method;
    private boolean closed;
    private String recordedError;

    private final StatsRecorder statsRecorder;
    private final Tagger tagger;
    private final Tracer tracer;

    TrackingOperation(String method) {
      this(method, null);
    }

    TrackingOperation(String method, @Nullable String sql) {
      this(method, sql, Observability.statsRecorder, Observability.tagger, Observability.tracer);
    }

    // VisibleForTesting
    TrackingOperation(
        String method,
        @Nullable String sql,
        StatsRecorder statsRecorder,
        Tagger tagger,
        Tracer tracer) {
      startTimeNs = System.nanoTime();
      span = tracer.spanBuilder(method).startSpan();
      this.method = method;
      if (sql != null) {
        span.putAttribute("sql", AttributeValue.stringAttributeValue(sql));
      }
      this.statsRecorder = statsRecorder;
      this.tagger = tagger;
      this.tracer = tracer;
    }

    @SuppressWarnings("MustBeClosedChecker")
    Scope withSpan() {
      return tracer.withSpan(span);
    }

    void end() {
      if (closed) return;

      try {
        // Finally record the latency of the entire call,
        // as well as "status": "OK" for non-error calls.
        TagContextBuilder tagContextBuilder = tagger.currentBuilder();
        tagContextBuilder.put(
            JAVA_SQL_METHOD, TagValue.create(this.method), TAG_METADATA_TTL_UNLIMITED);

        if (recordedError == null) {
          tagContextBuilder.put(JAVA_SQL_STATUS, VALUE_OK, TAG_METADATA_TTL_UNLIMITED);
        } else {
          // Make valid tag value
          String errorTag = makeValidTagValue(recordedError);
          tagContextBuilder.put(
              JAVA_SQL_ERROR, TagValue.create(errorTag), TAG_METADATA_TTL_UNLIMITED);
          tagContextBuilder.put(JAVA_SQL_STATUS, VALUE_ERROR, TAG_METADATA_TTL_UNLIMITED);
        }

        long totalTimeNs = System.nanoTime() - this.startTimeNs;
        double timeSpentMs = ((double) totalTimeNs) / 1e6;

        // Now finally record all the stats the same tags.
        recordStatWithTags(timeSpentMs, tagContextBuilder.build());
      } finally {
        span.end();
        closed = true;
      }
    }

    // Annotates the underlying span with the description of the exception. The actual ending
    // will be performed by end.
    void recordException(Exception e) {
      recordedError = e.toString();
      span.setStatus(Status.UNKNOWN.withDescription(recordedError));
    }

    private void recordStatWithTags(double value, TagContext tagContext) {
      statsRecorder.newMeasureMap().put(Observability.MEASURE_LATENCY_MS, value).record(tagContext);
    }
  }

  // Makes sure the tag value will be accepted as a valid tag value by TagValue::create
  // This will limit the string to TagValue.MAX_LENGTH and if any character is not in
  // the
  private static String makeValidTagValue(String tagValue) {
    int length = Math.min(tagValue.length(), TagValue.MAX_LENGTH);
    StringBuilder validTagValue = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      char ch = tagValue.charAt(i);
      if (isPrintableChar(ch)) {
        validTagValue.append(ch);
      } else {
        validTagValue.append(' ');
      }
    }
    return validTagValue.toString();
  }

  private static boolean isPrintableChar(char ch) {
    return ch >= ' ' && ch <= '~';
  }

  static TrackingOperation createRoundtripTrackingSpan(String method) {
    return new TrackingOperation(method);
  }

  static TrackingOperation createRoundtripTrackingSpan(
      String method, boolean canRecordSQL, String sql) {
    return new TrackingOperation(method, canRecordSQL ? sql : null);
  }

  public static void registerAllViews() {
    registerAllViews(Stats.getViewManager());
  }

  // VisibleForTesting
  static void registerAllViews(ViewManager viewManager) {
    for (View v : Arrays.asList(SQL_CLIENT_LATENCY_VIEW, SQL_CLIENT_CALLS_VIEW)) {
      viewManager.registerView(v);
    }
  }
}
