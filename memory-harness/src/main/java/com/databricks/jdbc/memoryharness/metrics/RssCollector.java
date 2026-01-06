package com.databricks.jdbc.memoryharness.metrics;

import com.databricks.jdbc.memoryharness.util.ProcessUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Collects RSS (Resident Set Size) metrics.
 * Tracks peak and final RSS from /proc/<pid>/status or via ps command.
 */
public final class RssCollector implements MetricsCollector {

    private static final String NAME = "rss";

    // Track peak RSS during workload execution
    private long peakRssBytes = 0;
    private long initialRssBytes = 0;

    /**
     * Captures the initial RSS.
     * Call this before running workloads.
     */
    public void captureInitial() {
        long pid = ProcessUtils.getCurrentPid();
        initialRssBytes = ProcessUtils.readRssBytes(pid);
        peakRssBytes = initialRssBytes;
        System.out.println("[RSS] Initial RSS: " + formatBytes(initialRssBytes));
    }

    /**
     * Updates the peak RSS if current RSS is higher.
     * Can be called periodically during workload execution.
     */
    public void updatePeak() {
        long pid = ProcessUtils.getCurrentPid();
        long currentRss = ProcessUtils.readRssBytes(pid);
        if (currentRss > peakRssBytes) {
            peakRssBytes = currentRss;
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> collect() throws MetricsException {
        Map<String, Object> metrics = new HashMap<>();
        long pid = ProcessUtils.getCurrentPid();

        // Get current RSS
        long currentRssBytes = ProcessUtils.readRssBytes(pid);
        metrics.put("final_rss_bytes", currentRssBytes);
        metrics.put("initial_rss_bytes", initialRssBytes);

        // Get peak RSS (either tracked or from /proc)
        long peakFromProc = ProcessUtils.readPeakRssBytes(pid);
        long actualPeak = Math.max(peakRssBytes, peakFromProc);
        metrics.put("peak_rss_bytes", actualPeak);

        // Calculate growth
        if (initialRssBytes > 0) {
            long growth = currentRssBytes - initialRssBytes;
            metrics.put("rss_growth_bytes", growth);
            double growthPercent = ((double) growth / initialRssBytes) * 100;
            metrics.put("rss_growth_percent", growthPercent);
        }

        // Get additional memory info from /proc if available
        Map<String, Long> procStatus = ProcessUtils.readProcStatus(pid);
        if (!procStatus.isEmpty()) {
            for (Map.Entry<String, Long> entry : procStatus.entrySet()) {
                metrics.put("proc_" + entry.getKey().toLowerCase() + "_bytes", entry.getValue());
            }
        }

        System.out.println("[RSS] Collection complete: final=" + formatBytes(currentRssBytes) +
                ", peak=" + formatBytes(actualPeak) +
                ", initial=" + formatBytes(initialRssBytes));

        return metrics;
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }
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

