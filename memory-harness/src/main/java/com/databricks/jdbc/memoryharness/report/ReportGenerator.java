package com.databricks.jdbc.memoryharness.report;

import com.databricks.jdbc.memoryharness.workload.WorkloadResult;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Generates JSON reports from collected metrics.
 * Uses manual JSON generation to avoid external dependencies.
 */
public final class ReportGenerator {

    private final String outputDirectory;

    public ReportGenerator(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * Writes all reports to the output directory.
     */
    public void generateReports(
            ReportModel.RunMetadata metadata,
            List<WorkloadResult> workloadResults,
            Map<String, Object> heapMetrics,
            Map<String, Object> gcMetrics,
            Map<String, Object> allocationMetrics,
            Map<String, Object> nativeMemoryMetrics,
            Map<String, Object> rssMetrics,
            Map<String, Object> metaspaceMetrics
    ) throws IOException {
        // Create output directory
        Path outputPath = Paths.get(outputDirectory);
        Files.createDirectories(outputPath);

        System.out.println("[ReportGenerator] Writing reports to: " + outputPath.toAbsolutePath());

        // 1. Run metadata
        writeJsonFile(outputPath.resolve("run-metadata.json"), metadata.toMap());

        // 2. Heap after GC
        writeJsonFile(outputPath.resolve("heap-after-gc.json"), filterRawOutput(heapMetrics));

        // 3. GC stats
        writeJsonFile(outputPath.resolve("gc-stats.json"), gcMetrics);

        // 4. Allocation rate
        writeJsonFile(outputPath.resolve("allocation-rate.json"), allocationMetrics);

        // 5. Native memory
        writeJsonFile(outputPath.resolve("native-memory.json"), filterRawOutput(nativeMemoryMetrics));

        // 6. RSS
        writeJsonFile(outputPath.resolve("rss.json"), rssMetrics);

        // 7. Class/Metaspace
        writeJsonFile(outputPath.resolve("class-metaspace.json"), metaspaceMetrics);

        // 8. Summary
        List<ReportModel.WorkloadSummary> workloadSummaries = new ArrayList<>();
        for (WorkloadResult result : workloadResults) {
            workloadSummaries.add(new ReportModel.WorkloadSummary(result));
        }

        ReportModel.Summary summary = new ReportModel.Summary(
                metadata,
                workloadSummaries,
                heapMetrics,
                gcMetrics,
                nativeMemoryMetrics,
                rssMetrics,
                metaspaceMetrics,
                allocationMetrics
        );
        writeJsonFile(outputPath.resolve("summary.json"), summary.toMap());

        System.out.println("[ReportGenerator] All reports written successfully");
    }

    /**
     * Filters out raw_output field for cleaner JSON.
     */
    private Map<String, Object> filterRawOutput(Map<String, Object> metrics) {
        Map<String, Object> filtered = new LinkedHashMap<>(metrics);
        filtered.remove("raw_output");
        return filtered;
    }

    /**
     * Writes a map as formatted JSON to a file.
     */
    private void writeJsonFile(Path path, Map<String, Object> data) throws IOException {
        String json = toJson(data, 0);
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        System.out.println("  - Wrote: " + path.getFileName());
    }

    /**
     * Converts a map to JSON string with indentation.
     * Simple implementation without external dependencies.
     */
    private String toJson(Object obj, int indent) {
        StringBuilder sb = new StringBuilder();
        toJson(obj, indent, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void toJson(Object obj, int indent, StringBuilder sb) {
        String indentStr = "  ".repeat(indent);
        String nextIndent = "  ".repeat(indent + 1);

        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            sb.append("{\n");
            Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> entry = it.next();
                sb.append(nextIndent).append("\"").append(escapeJson(entry.getKey())).append("\": ");
                toJson(entry.getValue(), indent + 1, sb);
                if (it.hasNext()) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(indentStr).append("}");
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            sb.append("[\n");
            Iterator<Object> it = list.iterator();
            while (it.hasNext()) {
                sb.append(nextIndent);
                toJson(it.next(), indent + 1, sb);
                if (it.hasNext()) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(indentStr).append("]");
        } else if (obj instanceof String) {
            sb.append("\"").append(escapeJson((String) obj)).append("\"");
        } else if (obj instanceof Number) {
            if (obj instanceof Double || obj instanceof Float) {
                double val = ((Number) obj).doubleValue();
                if (Double.isNaN(val) || Double.isInfinite(val)) {
                    sb.append("null");
                } else {
                    sb.append(obj);
                }
            } else {
                sb.append(obj);
            }
        } else if (obj instanceof Boolean) {
            sb.append(obj);
        } else {
            sb.append("\"").append(escapeJson(obj.toString())).append("\"");
        }
    }

    /**
     * Escapes special characters in JSON strings.
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}

