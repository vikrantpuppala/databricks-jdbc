package com.databricks.jdbc.memoryharness.workload;

/**
 * Exception thrown when a workload fails to execute.
 */
public class WorkloadException extends Exception {

    public WorkloadException(String message) {
        super(message);
    }

    public WorkloadException(String message, Throwable cause) {
        super(message, cause);
    }
}

