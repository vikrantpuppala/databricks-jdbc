package com.databricks.jdbc.memoryharness.report;

import com.databricks.jdbc.memoryharness.util.ProcessUtils;
import com.databricks.jdbc.memoryharness.workload.WorkloadResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Data models for the memory harness report.
 */
public final class ReportModel {

    private ReportModel() {
        // Utility class
    }

    /**
     * Run metadata containing environment and configuration info.
     */
    public static class RunMetadata {
        private final String timestamp;
        private final String gitSha;
        private final String gitBranch;
        private final String jvmVersion;
        private final String jvmFlags;
        private final String osInfo;
        private final long pid;
        private final Map<String, String> configuration;

        private RunMetadata(Builder builder) {
            this.timestamp = builder.timestamp;
            this.gitSha = builder.gitSha;
            this.gitBranch = builder.gitBranch;
            this.jvmVersion = builder.jvmVersion;
            this.jvmFlags = builder.jvmFlags;
            this.osInfo = builder.osInfo;
            this.pid = builder.pid;
            this.configuration = Collections.unmodifiableMap(new LinkedHashMap<>(builder.configuration));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", timestamp);
            map.put("git_sha", gitSha);
            map.put("git_branch", gitBranch);
            map.put("jvm_version", jvmVersion);
            map.put("jvm_flags", jvmFlags);
            map.put("os_info", osInfo);
            map.put("pid", pid);
            map.put("configuration", configuration);
            return map;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String timestamp;
            private String gitSha;
            private String gitBranch;
            private String jvmVersion;
            private String jvmFlags;
            private String osInfo;
            private long pid;
            private Map<String, String> configuration = new LinkedHashMap<>();

            public Builder timestamp(String timestamp) {
                this.timestamp = timestamp;
                return this;
            }

            public Builder gitSha(String gitSha) {
                this.gitSha = gitSha;
                return this;
            }

            public Builder gitBranch(String gitBranch) {
                this.gitBranch = gitBranch;
                return this;
            }

            public Builder jvmVersion(String jvmVersion) {
                this.jvmVersion = jvmVersion;
                return this;
            }

            public Builder jvmFlags(String jvmFlags) {
                this.jvmFlags = jvmFlags;
                return this;
            }

            public Builder osInfo(String osInfo) {
                this.osInfo = osInfo;
                return this;
            }

            public Builder pid(long pid) {
                this.pid = pid;
                return this;
            }

            public Builder configuration(Map<String, String> configuration) {
                this.configuration = new LinkedHashMap<>(configuration);
                return this;
            }

            public Builder addConfiguration(String key, String value) {
                this.configuration.put(key, value);
                return this;
            }

            public RunMetadata build() {
                return new RunMetadata(this);
            }
        }
    }

    /**
     * Creates run metadata by inspecting the current environment.
     */
    public static RunMetadata createRunMetadata(Map<String, String> configOverrides) {
        return RunMetadata.builder()
                .timestamp(Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT))
                .gitSha(getGitSha())
                .gitBranch(getGitBranch())
                .jvmVersion(ProcessUtils.getJvmVersion())
                .jvmFlags(getJvmFlags())
                .osInfo(ProcessUtils.getOsInfo())
                .pid(ProcessUtils.getCurrentPid())
                .configuration(configOverrides != null ? configOverrides : Collections.emptyMap())
                .build();
    }

    /**
     * Gets the current git SHA.
     */
    private static String getGitSha() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line != null ? line.trim() : "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Gets the current git branch.
     */
    private static String getGitBranch() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line != null ? line.trim() : "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Gets the JVM flags from the command line.
     */
    private static String getJvmFlags() {
        List<String> inputArgs = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        return String.join(" ", inputArgs);
    }

    /**
     * Summary of a single workload execution.
     */
    public static class WorkloadSummary {
        private final String name;
        private final long durationMs;
        private final long totalOperations;
        private final long successfulOperations;
        private final long failedOperations;
        private final double operationsPerSecond;
        private final long peakHeapBytes;
        private final long rowsProcessed;

        public WorkloadSummary(WorkloadResult result) {
            this.name = result.getWorkloadName();
            this.durationMs = result.getDurationMillis();
            this.totalOperations = result.getTotalOperations();
            this.successfulOperations = result.getSuccessfulOperations();
            this.failedOperations = result.getFailedOperations();
            this.operationsPerSecond = result.getOperationsPerSecond();
            this.peakHeapBytes = result.getPeakHeapBytes();
            this.rowsProcessed = result.getRowsProcessed();
        }

        public long getPeakHeapBytes() {
            return peakHeapBytes;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("duration_ms", durationMs);
            map.put("total_operations", totalOperations);
            map.put("successful_operations", successfulOperations);
            map.put("failed_operations", failedOperations);
            map.put("operations_per_second", operationsPerSecond);
            if (peakHeapBytes > 0) {
                map.put("peak_heap_bytes", peakHeapBytes);
            }
            if (rowsProcessed > 0) {
                map.put("rows_processed", rowsProcessed);
            }
            return map;
        }
    }

    /**
     * Overall summary report.
     */
    public static class Summary {
        private final RunMetadata metadata;
        private final List<WorkloadSummary> workloads;
        private final Map<String, Object> heapMetrics;
        private final Map<String, Object> gcMetrics;
        private final Map<String, Object> nativeMemoryMetrics;
        private final Map<String, Object> rssMetrics;
        private final Map<String, Object> metaspaceMetrics;
        private final Map<String, Object> allocationMetrics;

        public Summary(RunMetadata metadata,
                       List<WorkloadSummary> workloads,
                       Map<String, Object> heapMetrics,
                       Map<String, Object> gcMetrics,
                       Map<String, Object> nativeMemoryMetrics,
                       Map<String, Object> rssMetrics,
                       Map<String, Object> metaspaceMetrics,
                       Map<String, Object> allocationMetrics) {
            this.metadata = metadata;
            this.workloads = workloads;
            this.heapMetrics = heapMetrics;
            this.gcMetrics = gcMetrics;
            this.nativeMemoryMetrics = nativeMemoryMetrics;
            this.rssMetrics = rssMetrics;
            this.metaspaceMetrics = metaspaceMetrics;
            this.allocationMetrics = allocationMetrics;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("metadata", metadata.toMap());

            List<Map<String, Object>> workloadMaps = new ArrayList<>();
            for (WorkloadSummary w : workloads) {
                workloadMaps.add(w.toMap());
            }
            map.put("workloads", workloadMaps);

            // Add key metrics summary (human-readable)
            Map<String, Object> keyMetrics = new LinkedHashMap<>();

            // Heap summary
            if (heapMetrics.containsKey("heap_used_bytes")) {
                keyMetrics.put("heap_used_bytes", heapMetrics.get("heap_used_bytes"));
            } else if (heapMetrics.containsKey("mxbean_heap_used_bytes")) {
                keyMetrics.put("heap_used_bytes", heapMetrics.get("mxbean_heap_used_bytes"));
            }

            // GC summary
            if (gcMetrics.containsKey("delta_total_gc_count")) {
                keyMetrics.put("gc_count", gcMetrics.get("delta_total_gc_count"));
            }
            if (gcMetrics.containsKey("delta_total_gc_time_ms")) {
                keyMetrics.put("gc_time_ms", gcMetrics.get("delta_total_gc_time_ms"));
            }

            // RSS summary
            if (rssMetrics.containsKey("final_rss_bytes")) {
                keyMetrics.put("rss_bytes", rssMetrics.get("final_rss_bytes"));
            }
            if (rssMetrics.containsKey("peak_rss_bytes")) {
                keyMetrics.put("peak_rss_bytes", rssMetrics.get("peak_rss_bytes"));
            }

            // Metaspace summary
            if (metaspaceMetrics.containsKey("metaspace_used_bytes")) {
                keyMetrics.put("metaspace_used_bytes", metaspaceMetrics.get("metaspace_used_bytes"));
            }
            if (metaspaceMetrics.containsKey("loaded_class_count")) {
                keyMetrics.put("loaded_class_count", metaspaceMetrics.get("loaded_class_count"));
            }

            // Allocation summary
            if (allocationMetrics.containsKey("estimated_allocation_bytes")) {
                keyMetrics.put("estimated_allocation_bytes", allocationMetrics.get("estimated_allocation_bytes"));
            }
            if (allocationMetrics.containsKey("allocation_rate_mb_per_sec")) {
                keyMetrics.put("allocation_rate_mb_per_sec", allocationMetrics.get("allocation_rate_mb_per_sec"));
            }

            // Peak heap during workload (max across all workloads)
            long maxPeakHeap = 0;
            for (WorkloadSummary w : workloads) {
                if (w.getPeakHeapBytes() > maxPeakHeap) {
                    maxPeakHeap = w.getPeakHeapBytes();
                }
            }
            if (maxPeakHeap > 0) {
                keyMetrics.put("peak_heap_during_workload_bytes", maxPeakHeap);
            }

            map.put("key_metrics", keyMetrics);

            return map;
        }
    }
}

