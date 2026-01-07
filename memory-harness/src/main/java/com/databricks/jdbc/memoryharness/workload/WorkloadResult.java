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
    private final long peakHeapBytes;
    private final long rowsProcessed;

    private WorkloadResult(Builder builder) {
        this.workloadName = builder.workloadName;
        this.startTimeMillis = builder.startTimeMillis;
        this.endTimeMillis = builder.endTimeMillis;
        this.totalOperations = builder.totalOperations;
        this.successfulOperations = builder.successfulOperations;
        this.failedOperations = builder.failedOperations;
        this.peakHeapBytes = builder.peakHeapBytes;
        this.rowsProcessed = builder.rowsProcessed;
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

    /**
     * Returns the peak heap usage in bytes during workload execution.
     * Returns 0 if peak heap was not tracked.
     */
    public long getPeakHeapBytes() {
        return peakHeapBytes;
    }

    /**
     * Returns the number of rows processed during the workload.
     * Returns 0 if not applicable to the workload type.
     */
    public long getRowsProcessed() {
        return rowsProcessed;
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
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "WorkloadResult{name='%s', duration=%dms, operations=%d (success=%d, failed=%d), ops/sec=%.2f",
                workloadName, getDurationMillis(), totalOperations, successfulOperations, failedOperations, getOperationsPerSecond()
        ));
        if (peakHeapBytes > 0) {
            sb.append(String.format(", peakHeap=%d bytes", peakHeapBytes));
        }
        if (rowsProcessed > 0) {
            sb.append(String.format(", rows=%d", rowsProcessed));
        }
        sb.append("}");
        return sb.toString();
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
        private long peakHeapBytes;
        private long rowsProcessed;

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

        public Builder peakHeapBytes(long peakHeapBytes) {
            this.peakHeapBytes = peakHeapBytes;
            return this;
        }

        public Builder rowsProcessed(long rowsProcessed) {
            this.rowsProcessed = rowsProcessed;
            return this;
        }

        public WorkloadResult build() {
            return new WorkloadResult(this);
        }
    }
}

