package com.databricks.jdbc.memoryharness.metrics;

import com.databricks.jdbc.memoryharness.util.JcmdExecutor;
import com.databricks.jdbc.memoryharness.util.ProcessUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects native memory metrics using jcmd VM.native_memory.
 * Requires -XX:NativeMemoryTracking=summary to be enabled.
 */
public final class NativeMemoryCollector implements MetricsCollector {

    private static final String NAME = "native-memory";

    // Pattern to parse NMT output
    // Example: "-                 Java Heap (reserved=2097152KB, committed=2097152KB)"
    // Example: "Total: reserved=3456789KB, committed=234567KB"
    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "^-?\\s*([^(]+)\\s*\\(reserved=(\\d+)KB,\\s*committed=(\\d+)KB\\)"
    );
    private static final Pattern TOTAL_PATTERN = Pattern.compile(
            "^Total:\\s*reserved=(\\d+)KB,\\s*committed=(\\d+)KB"
    );

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> collect() throws MetricsException {
        Map<String, Object> metrics = new HashMap<>();
        long pid = ProcessUtils.getCurrentPid();

        try {
            List<String> nmtOutput = JcmdExecutor.getNativeMemorySummary(pid);
            parseNmtOutput(nmtOutput, metrics);
            System.out.println("[NativeMemory] Collection complete");
        } catch (JcmdExecutor.JcmdException e) {
            // NMT might not be enabled
            String message = e.getMessage();
            if (message.contains("not enabled") || message.contains("Native memory tracking")) {
                System.err.println("[NativeMemory] NMT not enabled. Run with -XX:NativeMemoryTracking=summary");
                metrics.put("error", "NMT not enabled");
                metrics.put("nmt_enabled", false);
            } else {
                throw new MetricsException("Failed to collect native memory metrics", e);
            }
        }

        return metrics;
    }

    private void parseNmtOutput(List<String> lines, Map<String, Object> metrics) {
        metrics.put("nmt_enabled", true);

        for (String line : lines) {
            // Check for total line
            Matcher totalMatcher = TOTAL_PATTERN.matcher(line);
            if (totalMatcher.find()) {
                long reservedKb = Long.parseLong(totalMatcher.group(1));
                long committedKb = Long.parseLong(totalMatcher.group(2));
                metrics.put("total_reserved_bytes", reservedKb * 1024);
                metrics.put("total_committed_bytes", committedKb * 1024);
                continue;
            }

            // Check for category lines
            Matcher categoryMatcher = CATEGORY_PATTERN.matcher(line);
            if (categoryMatcher.find()) {
                String category = categoryMatcher.group(1).trim();
                long reservedKb = Long.parseLong(categoryMatcher.group(2));
                long committedKb = Long.parseLong(categoryMatcher.group(3));

                String sanitizedCategory = sanitizeCategory(category);
                metrics.put(sanitizedCategory + "_reserved_bytes", reservedKb * 1024);
                metrics.put(sanitizedCategory + "_committed_bytes", committedKb * 1024);
            }
        }

        // Store raw output for debugging
        metrics.put("raw_output", String.join("\n", lines));
    }

    private String sanitizeCategory(String category) {
        return category.toLowerCase()
                .replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }
}

