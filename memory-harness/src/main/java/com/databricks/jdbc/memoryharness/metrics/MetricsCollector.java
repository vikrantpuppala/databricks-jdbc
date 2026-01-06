package com.databricks.jdbc.memoryharness.metrics;

import java.util.Map;

/**
 * Interface for collecting memory and performance metrics.
 */
public interface MetricsCollector {

    /**
     * Returns the name of this collector.
     */
    String getName();

    /**
     * Collects metrics and returns them as a map.
     * All numeric values should be normalized to bytes or milliseconds.
     *
     * @return map of metric names to values
     * @throws MetricsException if collection fails
     */
    Map<String, Object> collect() throws MetricsException;

    /**
     * Exception thrown when metrics collection fails.
     */
    class MetricsException extends Exception {
        public MetricsException(String message) {
            super(message);
        }

        public MetricsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

