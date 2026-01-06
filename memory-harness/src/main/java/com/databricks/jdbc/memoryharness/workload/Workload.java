package com.databricks.jdbc.memoryharness.workload;

import com.databricks.jdbc.memoryharness.config.HarnessConfig;

/**
 * Interface for JDBC workloads used in memory benchmarking.
 * Each workload executes a specific pattern of JDBC operations.
 */
public interface Workload {

    /**
     * Returns the unique name of this workload.
     * Used for logging and report identification.
     */
    String getName();

    /**
     * Executes the workload with the given configuration.
     * Implementations should be deterministic and not use randomization.
     *
     * @param config the harness configuration
     * @return result containing execution statistics
     * @throws WorkloadException if the workload fails
     */
    WorkloadResult execute(HarnessConfig config) throws WorkloadException;

    /**
     * Returns a description of what this workload tests.
     */
    String getDescription();
}

