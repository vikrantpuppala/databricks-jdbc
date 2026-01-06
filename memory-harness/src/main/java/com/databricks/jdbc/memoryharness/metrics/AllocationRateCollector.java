package com.databricks.jdbc.memoryharness.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects allocation rate and Eden churn metrics.
 * Estimates allocation based on GC activity and Eden pool usage.
 */
public final class AllocationRateCollector implements MetricsCollector {

    private static final String NAME = "allocation-rate";

    // Baseline values
    private long startTimeMs = 0;
    private long baselineYoungGcCount = 0;
    private long baselineEdenUsed = 0;

    /**
     * Captures baseline for allocation tracking.
     */
    public void captureBaseline() {
        startTimeMs = System.currentTimeMillis();

        // Get young GC count
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String name = gcBean.getName().toLowerCase();
            if (name.contains("young") || name.contains("scavenge") ||
                    name.contains("parnew") || name.contains("copy")) {
                baselineYoungGcCount = gcBean.getCollectionCount();
                break;
            }
        }

        // Get Eden usage
        baselineEdenUsed = getEdenUsed();

        System.out.println("[AllocationRate] Baseline captured: youngGc=" + baselineYoungGcCount +
                ", edenUsed=" + formatBytes(baselineEdenUsed));
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> collect() throws MetricsException {
        Map<String, Object> metrics = new HashMap<>();

        long currentTimeMs = System.currentTimeMillis();
        long durationMs = currentTimeMs - startTimeMs;

        // Get current young GC count
        long currentYoungGcCount = 0;
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String name = gcBean.getName().toLowerCase();
            if (name.contains("young") || name.contains("scavenge") ||
                    name.contains("parnew") || name.contains("copy")) {
                currentYoungGcCount = gcBean.getCollectionCount();
                break;
            }
        }

        long deltaYoungGc = currentYoungGcCount - baselineYoungGcCount;

        // Get Eden pool info
        long edenMax = 0;
        long edenUsed = 0;
        List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean pool : memoryPools) {
            String name = pool.getName().toLowerCase();
            if (name.contains("eden")) {
                edenUsed = pool.getUsage().getUsed();
                edenMax = pool.getUsage().getMax() > 0 ? pool.getUsage().getMax() : pool.getUsage().getCommitted();
                metrics.put("eden_used_bytes", edenUsed);
                metrics.put("eden_max_bytes", edenMax);
                metrics.put("eden_committed_bytes", pool.getUsage().getCommitted());

                if (pool.getPeakUsage() != null) {
                    metrics.put("eden_peak_used_bytes", pool.getPeakUsage().getUsed());
                }
                break;
            }
        }

        // Estimate total allocation (young GC count * eden size + current eden usage)
        // This is a rough estimate - each young GC means Eden was filled
        long estimatedAllocation = (deltaYoungGc * edenMax) + edenUsed;
        metrics.put("estimated_allocation_bytes", estimatedAllocation);
        metrics.put("young_gc_count_delta", deltaYoungGc);
        metrics.put("duration_ms", durationMs);

        // Calculate allocation rate (bytes per second)
        if (durationMs > 0) {
            double allocationRateBytesPerSec = (double) estimatedAllocation / (durationMs / 1000.0);
            metrics.put("allocation_rate_bytes_per_sec", allocationRateBytesPerSec);
            metrics.put("allocation_rate_mb_per_sec", allocationRateBytesPerSec / (1024 * 1024));
        }

        // Eden churn (how many times Eden was collected)
        metrics.put("eden_churn_count", deltaYoungGc);

        System.out.println("[AllocationRate] Estimated allocation: " + formatBytes(estimatedAllocation) +
                " over " + (durationMs / 1000) + "s, " + deltaYoungGc + " young GCs");

        return metrics;
    }

    private long getEdenUsed() {
        List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean pool : memoryPools) {
            if (pool.getName().toLowerCase().contains("eden")) {
                return pool.getUsage().getUsed();
            }
        }
        return 0;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

