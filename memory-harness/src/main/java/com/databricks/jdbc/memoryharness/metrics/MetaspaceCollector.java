package com.databricks.jdbc.memoryharness.metrics;

import com.databricks.jdbc.memoryharness.util.JcmdExecutor;
import com.databricks.jdbc.memoryharness.util.ProcessUtils;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects Metaspace and class loading metrics.
 * Extracts data from heap info and native memory output.
 */
public final class MetaspaceCollector implements MetricsCollector {

    private static final String NAME = "class-metaspace";

    // Patterns for parsing metaspace info
    // From GC.heap_info: "Metaspace       used 12345K, ..."
    private static final Pattern METASPACE_USED_PATTERN = Pattern.compile(
            "Metaspace\\s+used\\s+(\\d+)K"
    );
    private static final Pattern METASPACE_COMMITTED_PATTERN = Pattern.compile(
            "Metaspace.*committed\\s+(\\d+)K"
    );

    // Baseline for class loading
    private long baselineLoadedClassCount = 0;
    private long baselineUnloadedClassCount = 0;

    /**
     * Captures baseline class loading stats.
     */
    public void captureBaseline() {
        ClassLoadingMXBean classLoadingMxBean = ManagementFactory.getClassLoadingMXBean();
        baselineLoadedClassCount = classLoadingMxBean.getTotalLoadedClassCount();
        baselineUnloadedClassCount = classLoadingMxBean.getUnloadedClassCount();
        System.out.println("[Metaspace] Baseline: loadedClasses=" + baselineLoadedClassCount +
                ", unloadedClasses=" + baselineUnloadedClassCount);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> collect() throws MetricsException {
        Map<String, Object> metrics = new HashMap<>();

        // Collect from MXBeans
        collectFromMxBeans(metrics);

        // Try to collect from jcmd for more detailed info
        collectFromJcmd(metrics);

        System.out.println("[Metaspace] Collection complete: currentLoaded=" +
                metrics.get("loaded_class_count") + ", metaspaceUsed=" +
                formatBytes((Long) metrics.getOrDefault("metaspace_used_bytes", 0L)));

        return metrics;
    }

    private void collectFromMxBeans(Map<String, Object> metrics) {
        // Class loading metrics
        ClassLoadingMXBean classLoadingMxBean = ManagementFactory.getClassLoadingMXBean();
        long currentLoaded = classLoadingMxBean.getLoadedClassCount();
        long totalLoaded = classLoadingMxBean.getTotalLoadedClassCount();
        long unloaded = classLoadingMxBean.getUnloadedClassCount();

        metrics.put("loaded_class_count", currentLoaded);
        metrics.put("total_loaded_class_count", totalLoaded);
        metrics.put("unloaded_class_count", unloaded);

        // Delta from baseline
        metrics.put("delta_loaded_class_count", totalLoaded - baselineLoadedClassCount);
        metrics.put("delta_unloaded_class_count", unloaded - baselineUnloadedClassCount);

        // Metaspace from memory pool MXBeans
        List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean pool : memoryPools) {
            String name = pool.getName().toLowerCase();
            if (name.contains("metaspace")) {
                metrics.put("metaspace_used_bytes", pool.getUsage().getUsed());
                metrics.put("metaspace_committed_bytes", pool.getUsage().getCommitted());
                metrics.put("metaspace_max_bytes", pool.getUsage().getMax());

                if (pool.getPeakUsage() != null) {
                    metrics.put("metaspace_peak_used_bytes", pool.getPeakUsage().getUsed());
                }
            } else if (name.contains("compressed class space")) {
                metrics.put("compressed_class_space_used_bytes", pool.getUsage().getUsed());
                metrics.put("compressed_class_space_committed_bytes", pool.getUsage().getCommitted());
            } else if (name.contains("code") && name.contains("cache")) {
                metrics.put("code_cache_used_bytes", pool.getUsage().getUsed());
                metrics.put("code_cache_committed_bytes", pool.getUsage().getCommitted());
            }
        }
    }

    private void collectFromJcmd(Map<String, Object> metrics) {
        long pid = ProcessUtils.getCurrentPid();

        try {
            // Try to get more detailed info from heap info
            List<String> heapInfo = JcmdExecutor.getHeapInfo(pid);
            for (String line : heapInfo) {
                Matcher usedMatcher = METASPACE_USED_PATTERN.matcher(line);
                if (usedMatcher.find()) {
                    long usedKb = Long.parseLong(usedMatcher.group(1));
                    metrics.put("jcmd_metaspace_used_bytes", usedKb * 1024);
                }

                Matcher committedMatcher = METASPACE_COMMITTED_PATTERN.matcher(line);
                if (committedMatcher.find()) {
                    long committedKb = Long.parseLong(committedMatcher.group(1));
                    metrics.put("jcmd_metaspace_committed_bytes", committedKb * 1024);
                }
            }

            // Also try to get from native memory
            List<String> nmtOutput = JcmdExecutor.getNativeMemorySummary(pid);
            for (String line : nmtOutput) {
                if (line.contains("Class")) {
                    // Parse class memory from NMT
                    Pattern classPattern = Pattern.compile("Class.*reserved=(\\d+)KB.*committed=(\\d+)KB");
                    Matcher classMatcher = classPattern.matcher(line);
                    if (classMatcher.find()) {
                        metrics.put("nmt_class_reserved_bytes", Long.parseLong(classMatcher.group(1)) * 1024);
                        metrics.put("nmt_class_committed_bytes", Long.parseLong(classMatcher.group(2)) * 1024);
                    }
                }
            }
        } catch (JcmdExecutor.JcmdException e) {
            // Non-fatal, we have MXBean data
            System.err.println("[Metaspace] Could not get jcmd data: " + e.getMessage());
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}

