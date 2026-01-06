package com.databricks.jdbc.memoryharness;

import com.databricks.jdbc.memoryharness.config.HarnessConfig;
import com.databricks.jdbc.memoryharness.metrics.*;
import com.databricks.jdbc.memoryharness.report.ReportGenerator;
import com.databricks.jdbc.memoryharness.report.ReportModel;
import com.databricks.jdbc.memoryharness.util.JcmdExecutor;
import com.databricks.jdbc.memoryharness.util.ProcessUtils;
import com.databricks.jdbc.memoryharness.workload.*;

import java.sql.DriverManager;
import java.util.*;

/**
 * Main entry point for the memory harness.
 * Orchestrates workload execution and metrics collection.
 *
 * <p>Usage:
 * <pre>
 * java -jar memory-harness.jar [workloads...]
 * </pre>
 *
 * <p>Workload options:
 * <ul>
 *   <li>connection-lifecycle - Connection open/close workload</li>
 *   <li>metadata-heavy - DatabaseMetaData workload</li>
 *   <li>resultset-iteration - ResultSet iteration workload</li>
 *   <li>mixed-steady-state - Mixed long-running workload</li>
 *   <li>all - Run all workloads (default)</li>
 * </ul>
 */
public final class MemoryHarnessMain {

    private static final String BANNER = 
            "╔══════════════════════════════════════════════════════════════╗\n" +
            "║     Databricks JDBC Driver - Memory Regression Harness       ║\n" +
            "╚══════════════════════════════════════════════════════════════╝\n";

    public static void main(String[] args) {
        System.out.println(BANNER);

        try {
            int exitCode = run(args);
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static int run(String[] args) throws Exception {
        // Parse arguments
        Set<String> requestedWorkloads = parseWorkloads(args);

        // Load configuration
        HarnessConfig config;
        try {
            config = HarnessConfig.fromEnvironment();
        } catch (IllegalStateException e) {
            System.err.println("Configuration error: " + e.getMessage());
            printUsage();
            return 1;
        }

        System.out.println("Configuration: " + config);
        System.out.println("PID: " + ProcessUtils.getCurrentPid());
        System.out.println("JVM: " + ProcessUtils.getJvmVersion());
        System.out.println("OS: " + ProcessUtils.getOsInfo());
        System.out.println();

        // Check jcmd availability
        if (!JcmdExecutor.isAvailable()) {
            System.err.println("WARNING: jcmd not available. Some metrics will be limited.");
        }

        // Initialize JDBC driver
        System.out.println("Loading JDBC driver...");
        try {
            Class.forName("com.databricks.client.jdbc.Driver");
            System.out.println("Driver loaded successfully");
        } catch (ClassNotFoundException e) {
            System.err.println("ERROR: Could not load Databricks JDBC driver. Ensure it's on the classpath.");
            return 1;
        }

        // Initialize collectors
        GcStatsCollector gcCollector = new GcStatsCollector();
        RssCollector rssCollector = new RssCollector();
        MetaspaceCollector metaspaceCollector = new MetaspaceCollector();
        AllocationRateCollector allocationCollector = new AllocationRateCollector();
        HeapMetricsCollector heapCollector = new HeapMetricsCollector();
        NativeMemoryCollector nativeMemoryCollector = new NativeMemoryCollector();

        // Capture baselines
        System.out.println("\n=== Capturing Baselines ===");
        gcCollector.captureBaseline();
        rssCollector.captureInitial();
        metaspaceCollector.captureBaseline();
        allocationCollector.captureBaseline();

        // Get available workloads
        Map<String, Workload> availableWorkloads = new LinkedHashMap<>();
        availableWorkloads.put("connection-lifecycle", new ConnectionLifecycleWorkload());
        availableWorkloads.put("metadata-heavy", new MetadataHeavyWorkload());
        availableWorkloads.put("resultset-iteration", new ResultSetIterationWorkload());
        availableWorkloads.put("mixed-steady-state", new MixedSteadyStateWorkload());

        // Determine which workloads to run
        List<Workload> workloadsToRun = new ArrayList<>();
        if (requestedWorkloads.contains("all")) {
            workloadsToRun.addAll(availableWorkloads.values());
        } else {
            for (String name : requestedWorkloads) {
                Workload workload = availableWorkloads.get(name);
                if (workload != null) {
                    workloadsToRun.add(workload);
                } else {
                    System.err.println("Unknown workload: " + name);
                }
            }
        }

        if (workloadsToRun.isEmpty()) {
            System.err.println("No workloads to run!");
            return 1;
        }

        // Run workloads
        System.out.println("\n=== Running Workloads ===");
        List<WorkloadResult> results = new ArrayList<>();

        for (Workload workload : workloadsToRun) {
            System.out.println("\n--- " + workload.getName() + " ---");
            System.out.println("Description: " + workload.getDescription());
            System.out.println();

            try {
                WorkloadResult result = workload.execute(config);
                results.add(result);
                System.out.println("Result: " + result);

                // Update RSS peak tracking
                rssCollector.updatePeak();

            } catch (WorkloadException e) {
                System.err.println("Workload failed: " + e.getMessage());
                e.printStackTrace();
                // Continue with other workloads
            }
        }

        // Collect final metrics
        System.out.println("\n=== Collecting Final Metrics ===");

        Map<String, Object> heapMetrics = collectSafely("Heap", heapCollector);
        Map<String, Object> gcMetrics = collectSafely("GC", gcCollector);
        Map<String, Object> allocationMetrics = collectSafely("Allocation", allocationCollector);
        Map<String, Object> nativeMemoryMetrics = collectSafely("NativeMemory", nativeMemoryCollector);
        Map<String, Object> rssMetrics = collectSafely("RSS", rssCollector);
        Map<String, Object> metaspaceMetrics = collectSafely("Metaspace", metaspaceCollector);

        // Generate reports
        System.out.println("\n=== Generating Reports ===");

        // Create run metadata with configuration
        Map<String, String> configMap = new LinkedHashMap<>();
        configMap.put("connection_lifecycle_iterations", String.valueOf(config.getConnectionLifecycleIterations()));
        configMap.put("connection_lifecycle_concurrency", String.valueOf(config.getConnectionLifecycleConcurrency()));
        configMap.put("metadata_iterations", String.valueOf(config.getMetadataIterations()));
        configMap.put("resultset_small_iterations", String.valueOf(config.getResultSetSmallIterations()));
        configMap.put("resultset_medium_iterations", String.valueOf(config.getResultSetMediumIterations()));
        configMap.put("resultset_medium_row_count", String.valueOf(config.getResultSetMediumRowCount()));
        configMap.put("mixed_workload_duration_seconds", String.valueOf(config.getMixedWorkloadDurationSeconds()));

        ReportModel.RunMetadata metadata = ReportModel.createRunMetadata(configMap);

        ReportGenerator reportGenerator = new ReportGenerator(config.getOutputDirectory());
        reportGenerator.generateReports(
                metadata,
                results,
                heapMetrics,
                gcMetrics,
                allocationMetrics,
                nativeMemoryMetrics,
                rssMetrics,
                metaspaceMetrics
        );

        // Print summary
        System.out.println("\n=== Summary ===");
        System.out.println("Workloads executed: " + results.size());
        for (WorkloadResult result : results) {
            System.out.println("  - " + result.getWorkloadName() + ": " +
                    result.getDurationMillis() + "ms, " +
                    result.getTotalOperations() + " ops (" +
                    result.getSuccessfulOperations() + " success, " +
                    result.getFailedOperations() + " failed)");
        }

        System.out.println("\nKey Metrics:");
        printKeyMetric("  Heap used (after GC)", heapMetrics, "heap_used_bytes", "mxbean_heap_used_bytes");
        printKeyMetric("  RSS (final)", rssMetrics, "final_rss_bytes");
        printKeyMetric("  RSS (peak)", rssMetrics, "peak_rss_bytes");
        printKeyMetric("  GC count (delta)", gcMetrics, "delta_total_gc_count");
        printKeyMetric("  GC time (delta)", gcMetrics, "delta_total_gc_time_ms");
        printKeyMetric("  Metaspace used", metaspaceMetrics, "metaspace_used_bytes");
        printKeyMetric("  Loaded classes", metaspaceMetrics, "loaded_class_count");
        printKeyMetric("  Est. allocation", allocationMetrics, "estimated_allocation_bytes");

        System.out.println("\nReports written to: " + config.getOutputDirectory());
        System.out.println("\n=== Memory Harness Complete ===\n");

        return 0;
    }

    private static Set<String> parseWorkloads(String[] args) {
        Set<String> workloads = new LinkedHashSet<>();
        if (args.length == 0) {
            workloads.add("all");
        } else {
            for (String arg : args) {
                if (arg.startsWith("-")) {
                    // Skip flags
                    continue;
                }
                workloads.add(arg.toLowerCase());
            }
            if (workloads.isEmpty()) {
                workloads.add("all");
            }
        }
        return workloads;
    }

    private static Map<String, Object> collectSafely(String name, MetricsCollector collector) {
        try {
            return collector.collect();
        } catch (MetricsCollector.MetricsException e) {
            System.err.println("Warning: Failed to collect " + name + " metrics: " + e.getMessage());
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("error", e.getMessage());
            return errorMap;
        }
    }

    private static void printKeyMetric(String label, Map<String, Object> metrics, String... keys) {
        for (String key : keys) {
            Object value = metrics.get(key);
            if (value != null) {
                if (value instanceof Long && (key.endsWith("_bytes") || key.contains("allocation"))) {
                    System.out.println(label + ": " + formatBytes((Long) value));
                } else if (value instanceof Long && key.endsWith("_ms")) {
                    System.out.println(label + ": " + value + " ms");
                } else {
                    System.out.println(label + ": " + value);
                }
                return;
            }
        }
        System.out.println(label + ": N/A");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static void printUsage() {
        System.out.println("\nUsage: java -jar memory-harness.jar [workloads...]");
        System.out.println("\nWorkloads:");
        System.out.println("  connection-lifecycle  - Connection open/close stress test");
        System.out.println("  metadata-heavy        - DatabaseMetaData operations");
        System.out.println("  resultset-iteration   - ResultSet iteration (small & medium)");
        System.out.println("  mixed-steady-state    - Long-running mixed workload");
        System.out.println("  all                   - Run all workloads (default)");
        System.out.println("\nRequired environment variables:");
        System.out.println("  HARNESS_JDBC_URL      - JDBC connection URL");
        System.out.println("  HARNESS_USERNAME      - Database username (or 'token')");
        System.out.println("  HARNESS_PASSWORD      - Database password or access token");
        System.out.println("\nOptional environment variables:");
        System.out.println("  HARNESS_CONN_ITERATIONS         - Connection lifecycle iterations (default: 100)");
        System.out.println("  HARNESS_CONN_CONCURRENCY        - Connection lifecycle concurrency (default: 4)");
        System.out.println("  HARNESS_METADATA_ITERATIONS     - Metadata workload iterations (default: 50)");
        System.out.println("  HARNESS_RS_SMALL_ITERATIONS     - Small result set iterations (default: 100)");
        System.out.println("  HARNESS_RS_MEDIUM_ITERATIONS    - Medium result set iterations (default: 20)");
        System.out.println("  HARNESS_RS_MEDIUM_ROWS          - Medium result set row count (default: 5000)");
        System.out.println("  HARNESS_MIXED_DURATION_SECONDS  - Mixed workload duration (default: 120)");
        System.out.println("  HARNESS_OUTPUT_DIR              - Output directory (default: memory-report)");
    }
}

