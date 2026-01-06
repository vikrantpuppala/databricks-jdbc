package com.databricks.jdbc.memoryharness.metrics;

import com.databricks.jdbc.memoryharness.util.JcmdExecutor;
import com.databricks.jdbc.memoryharness.util.ProcessUtils;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects heap memory metrics.
 * Triggers full GC before collection for steady-state measurement.
 */
public final class HeapMetricsCollector implements MetricsCollector {

    private static final String NAME = "heap-after-gc";

    // Pattern to parse heap info output
    // Example: " garbage-first heap   total 2097152K, used 45678K [0x..."
    private static final Pattern HEAP_TOTAL_PATTERN = Pattern.compile("total\\s+(\\d+)K");
    private static final Pattern HEAP_USED_PATTERN = Pattern.compile("used\\s+(\\d+)K");

    // Pattern for region info
    // Example: "  region size 2048K, 15 young (30720K), 3 survivors (6144K)"
    private static final Pattern REGION_SIZE_PATTERN = Pattern.compile("region size\\s+(\\d+)K");
    private static final Pattern YOUNG_PATTERN = Pattern.compile("(\\d+)\\s+young\\s+\\((\\d+)K\\)");
    private static final Pattern SURVIVORS_PATTERN = Pattern.compile("(\\d+)\\s+survivors\\s+\\((\\d+)K\\)");

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> collect() throws MetricsException {
        Map<String, Object> metrics = new HashMap<>();
        long pid = ProcessUtils.getCurrentPid();

        try {
            // First, trigger a full GC
            System.out.println("[HeapMetrics] Triggering full GC...");
            JcmdExecutor.triggerFullGc(pid);

            // Give GC a moment to complete
            Thread.sleep(500);

            // Now collect heap info
            List<String> heapInfo = JcmdExecutor.getHeapInfo(pid);

            // Parse jcmd output
            parseJcmdHeapInfo(heapInfo, metrics);

            // Also collect via MXBean for comparison
            collectMxBeanMetrics(metrics);

            System.out.println("[HeapMetrics] Collection complete: " + metrics);

        } catch (JcmdExecutor.JcmdException e) {
            System.err.println("[HeapMetrics] jcmd failed, falling back to MXBean: " + e.getMessage());
            collectMxBeanMetrics(metrics);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetricsException("Interrupted during heap metrics collection", e);
        }

        return metrics;
    }

    private void parseJcmdHeapInfo(List<String> lines, Map<String, Object> metrics) {
        long heapTotalBytes = 0;
        long heapUsedBytes = 0;
        long regionSizeBytes = 0;
        long youngBytes = 0;
        long survivorBytes = 0;

        for (String line : lines) {
            // Parse total heap
            Matcher totalMatcher = HEAP_TOTAL_PATTERN.matcher(line);
            if (totalMatcher.find()) {
                heapTotalBytes = Long.parseLong(totalMatcher.group(1)) * 1024;
            }

            // Parse used heap
            Matcher usedMatcher = HEAP_USED_PATTERN.matcher(line);
            if (usedMatcher.find()) {
                heapUsedBytes = Long.parseLong(usedMatcher.group(1)) * 1024;
            }

            // Parse region size
            Matcher regionMatcher = REGION_SIZE_PATTERN.matcher(line);
            if (regionMatcher.find()) {
                regionSizeBytes = Long.parseLong(regionMatcher.group(1)) * 1024;
            }

            // Parse young regions
            Matcher youngMatcher = YOUNG_PATTERN.matcher(line);
            if (youngMatcher.find()) {
                youngBytes = Long.parseLong(youngMatcher.group(2)) * 1024;
            }

            // Parse survivor regions
            Matcher survivorMatcher = SURVIVORS_PATTERN.matcher(line);
            if (survivorMatcher.find()) {
                survivorBytes = Long.parseLong(survivorMatcher.group(2)) * 1024;
            }
        }

        metrics.put("heap_total_bytes", heapTotalBytes);
        metrics.put("heap_used_bytes", heapUsedBytes);
        metrics.put("heap_free_bytes", heapTotalBytes - heapUsedBytes);

        if (regionSizeBytes > 0) {
            metrics.put("g1_region_size_bytes", regionSizeBytes);
        }
        if (youngBytes > 0) {
            metrics.put("g1_young_bytes", youngBytes);
        }
        if (survivorBytes > 0) {
            metrics.put("g1_survivor_bytes", survivorBytes);
        }

        // Store raw output for debugging
        metrics.put("raw_output", String.join("\n", lines));
    }

    private void collectMxBeanMetrics(Map<String, Object> metrics) {
        MemoryMXBean memoryMxBean = ManagementFactory.getMemoryMXBean();

        MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
        metrics.put("mxbean_heap_init_bytes", heapUsage.getInit());
        metrics.put("mxbean_heap_used_bytes", heapUsage.getUsed());
        metrics.put("mxbean_heap_committed_bytes", heapUsage.getCommitted());
        metrics.put("mxbean_heap_max_bytes", heapUsage.getMax());

        MemoryUsage nonHeapUsage = memoryMxBean.getNonHeapMemoryUsage();
        metrics.put("mxbean_nonheap_used_bytes", nonHeapUsage.getUsed());
        metrics.put("mxbean_nonheap_committed_bytes", nonHeapUsage.getCommitted());
    }
}

