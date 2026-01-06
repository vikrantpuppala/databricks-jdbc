package com.databricks.jdbc.memoryharness.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects GC statistics including count, pause time, and allocation churn.
 * Uses GarbageCollectorMXBean for portable metrics.
 */
public final class GcStatsCollector implements MetricsCollector {

    private static final String NAME = "gc-stats";

    // Baseline values captured at start
    private long baselineYoungGcCount = 0;
    private long baselineYoungGcTimeMs = 0;
    private long baselineOldGcCount = 0;
    private long baselineOldGcTimeMs = 0;

    /**
     * Captures the baseline GC stats.
     * Call this before running workloads to measure delta.
     */
    public void captureBaseline() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String name = gcBean.getName().toLowerCase();
            if (isYoungGc(name)) {
                baselineYoungGcCount = gcBean.getCollectionCount();
                baselineYoungGcTimeMs = gcBean.getCollectionTime();
            } else if (isOldGc(name)) {
                baselineOldGcCount = gcBean.getCollectionCount();
                baselineOldGcTimeMs = gcBean.getCollectionTime();
            }
        }

        System.out.println("[GcStats] Baseline captured: youngGc=" + baselineYoungGcCount +
                ", oldGc=" + baselineOldGcCount);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> collect() throws MetricsException {
        Map<String, Object> metrics = new HashMap<>();

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        long totalGcCount = 0;
        long totalGcTimeMs = 0;
        long youngGcCount = 0;
        long youngGcTimeMs = 0;
        long oldGcCount = 0;
        long oldGcTimeMs = 0;

        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String name = gcBean.getName();
            long count = gcBean.getCollectionCount();
            long timeMs = gcBean.getCollectionTime();

            if (count >= 0) {
                totalGcCount += count;
                totalGcTimeMs += timeMs;

                // Categorize by GC type
                String lowerName = name.toLowerCase();
                if (isYoungGc(lowerName)) {
                    youngGcCount = count;
                    youngGcTimeMs = timeMs;
                } else if (isOldGc(lowerName)) {
                    oldGcCount = count;
                    oldGcTimeMs = timeMs;
                }

                // Store per-collector metrics
                metrics.put("gc_" + sanitizeName(name) + "_count", count);
                metrics.put("gc_" + sanitizeName(name) + "_time_ms", timeMs);
            }
        }

        // Calculate deltas from baseline
        long deltaYoungGcCount = youngGcCount - baselineYoungGcCount;
        long deltaYoungGcTimeMs = youngGcTimeMs - baselineYoungGcTimeMs;
        long deltaOldGcCount = oldGcCount - baselineOldGcCount;
        long deltaOldGcTimeMs = oldGcTimeMs - baselineOldGcTimeMs;

        // Summary metrics
        metrics.put("total_gc_count", totalGcCount);
        metrics.put("total_gc_time_ms", totalGcTimeMs);
        metrics.put("young_gc_count", youngGcCount);
        metrics.put("young_gc_time_ms", youngGcTimeMs);
        metrics.put("old_gc_count", oldGcCount);
        metrics.put("old_gc_time_ms", oldGcTimeMs);

        // Delta metrics (since baseline)
        metrics.put("delta_young_gc_count", deltaYoungGcCount);
        metrics.put("delta_young_gc_time_ms", deltaYoungGcTimeMs);
        metrics.put("delta_old_gc_count", deltaOldGcCount);
        metrics.put("delta_old_gc_time_ms", deltaOldGcTimeMs);
        metrics.put("delta_total_gc_count", deltaYoungGcCount + deltaOldGcCount);
        metrics.put("delta_total_gc_time_ms", deltaYoungGcTimeMs + deltaOldGcTimeMs);

        // Calculate average pause time
        long deltaCount = deltaYoungGcCount + deltaOldGcCount;
        if (deltaCount > 0) {
            double avgPauseMs = (double) (deltaYoungGcTimeMs + deltaOldGcTimeMs) / deltaCount;
            metrics.put("avg_gc_pause_ms", avgPauseMs);
        }

        System.out.println("[GcStats] Collection complete: deltaYoungGc=" + deltaYoungGcCount +
                ", deltaOldGc=" + deltaOldGcCount + ", totalGcTimeMs=" + (deltaYoungGcTimeMs + deltaOldGcTimeMs));

        return metrics;
    }

    private boolean isYoungGc(String name) {
        // G1 Young, PS Scavenge, ParNew, etc.
        return name.contains("young") || name.contains("scavenge") ||
                name.contains("parnew") || name.contains("copy");
    }

    private boolean isOldGc(String name) {
        // G1 Old, PS MarkSweep, CMS, etc.
        return name.contains("old") || name.contains("marksweep") ||
                name.contains("cms") || name.contains("mark sweep");
    }

    private String sanitizeName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }
}

