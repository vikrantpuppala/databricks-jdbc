package com.databricks.jdbc.memoryharness.workload;

/**
 * Result of a workload execution.
 * Contains timing and operation count statistics.
 */
public final class WorkloadResult {

    private final String workloadName;
    private final long startTimeMillis;
    private final long endTimeMillis;
    private final long totalOperations;
    private final long successfulOperations;
    private final long failedOperations;

    private WorkloadResult(Builder builder) {
        this.workloadName = builder.workloadName;
        this.startTimeMillis = builder.startTimeMillis;
        this.endTimeMillis = builder.endTimeMillis;
        this.totalOperations = builder.totalOperations;
        this.successfulOperations = builder.successfulOperations;
        this.failedOperations = builder.failedOperations;
    }

    public String getWorkloadName() {
        return workloadName;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    public long getDurationMillis() {
        return endTimeMillis - startTimeMillis;
    }

    public long getTotalOperations() {
        return totalOperations;
    }

    public long getSuccessfulOperations() {
        return successfulOperations;
    }

    public long getFailedOperations() {
        return failedOperations;
    }

    public double getOperationsPerSecond() {
        long durationSeconds = getDurationMillis() / 1000;
        if (durationSeconds == 0) {
            return totalOperations;
        }
        return (double) totalOperations / durationSeconds;
    }

    @Override
    public String toString() {
        return String.format(
                "WorkloadResult{name='%s', duration=%dms, operations=%d (success=%d, failed=%d), ops/sec=%.2f}",
                workloadName, getDurationMillis(), totalOperations, successfulOperations, failedOperations, getOperationsPerSecond()
        );
    }

    public static Builder builder(String workloadName) {
        return new Builder(workloadName);
    }

    public static class Builder {
        private final String workloadName;
        private long startTimeMillis;
        private long endTimeMillis;
        private long totalOperations;
        private long successfulOperations;
        private long failedOperations;

        private Builder(String workloadName) {
            this.workloadName = workloadName;
        }

        public Builder startTimeMillis(long startTimeMillis) {
            this.startTimeMillis = startTimeMillis;
            return this;
        }

        public Builder endTimeMillis(long endTimeMillis) {
            this.endTimeMillis = endTimeMillis;
            return this;
        }

        public Builder totalOperations(long totalOperations) {
            this.totalOperations = totalOperations;
            return this;
        }

        public Builder successfulOperations(long successfulOperations) {
            this.successfulOperations = successfulOperations;
            return this;
        }

        public Builder failedOperations(long failedOperations) {
            this.failedOperations = failedOperations;
            return this;
        }

        public WorkloadResult build() {
            return new WorkloadResult(this);
        }
    }
}

