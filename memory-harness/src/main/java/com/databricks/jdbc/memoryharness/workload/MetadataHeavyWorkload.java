package com.databricks.jdbc.memoryharness.workload;

import com.databricks.jdbc.memoryharness.config.HarnessConfig;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Workload that repeatedly calls DatabaseMetaData methods.
 * Tests: getSchemas, getTables, getColumns, getPrimaryKeys
 * Runs for many iterations to expose retained objects.
 */
public final class MetadataHeavyWorkload implements Workload {

    private static final String NAME = "metadata-heavy";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Repeatedly calls DatabaseMetaData methods (getSchemas, getTables, getColumns, getPrimaryKeys)";
    }

    @Override
    public WorkloadResult execute(HarnessConfig config) throws WorkloadException {
        int iterations = config.getMetadataIterations();
        String jdbcUrl = config.getJdbcUrl();

        System.out.println("[" + NAME + "] Starting with " + iterations + " iterations");

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        // Use a single connection for all metadata operations
        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.getConnectionProperties())) {
            DatabaseMetaData metaData = conn.getMetaData();

            for (int i = 0; i < iterations; i++) {
                try {
                    executeMetadataOperations(metaData);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("[" + NAME + "] Iteration " + i + " failed: " + e.getMessage());
                    failCount.incrementAndGet();
                }

                if ((i + 1) % 10 == 0) {
                    System.out.println("[" + NAME + "] Progress: " + (i + 1) + "/" + iterations);
                }
            }
        } catch (Exception e) {
            throw new WorkloadException("Failed to establish connection for metadata workload", e);
        }

        long endTime = System.currentTimeMillis();

        // Each iteration has 4 metadata operations
        long totalOps = iterations * 4L;

        System.out.println("[" + NAME + "] Completed: iterations=" + iterations +
                " (success=" + successCount.get() + ", failed=" + failCount.get() + ")");

        return WorkloadResult.builder(NAME)
                .startTimeMillis(startTime)
                .endTimeMillis(endTime)
                .totalOperations(totalOps)
                .successfulOperations(successCount.get() * 4L)
                .failedOperations(failCount.get() * 4L)
                .build();
    }

    private void executeMetadataOperations(DatabaseMetaData metaData) throws Exception {
        // 1. getSchemas - get all schemas in 'main' catalog
        try (ResultSet rs = metaData.getSchemas("samples", "%")) {
            int count = 0;
            while (rs.next()) {
                rs.getString("TABLE_SCHEM");
                rs.getString("TABLE_CATALOG");
                count++;
            }
        }

        // 2. getTables - get tables in the default schema
        try (ResultSet rs = metaData.getTables("samples", "tpch", "%", null)) {
            int count = 0;
            while (rs.next()) {
                rs.getString("TABLE_NAME");
                rs.getString("TABLE_TYPE");
                rs.getString("TABLE_CAT");
                rs.getString("TABLE_SCHEM");
                count++;
            }
        }

        // 3. getColumns - get columns for a system table (to ensure it exists)
        try (ResultSet rs = metaData.getColumns("samples", "tpch", "lineitem", "%")) {
            int count = 0;
            while (rs.next()) {
                rs.getString("COLUMN_NAME");
                rs.getInt("DATA_TYPE");
                rs.getString("TYPE_NAME");
                rs.getInt("COLUMN_SIZE");
                count++;
            }
        }

        // 4. getPrimaryKeys - attempt to get primary keys (may return empty)
        try (ResultSet rs = metaData.getPrimaryKeys("samples", "tpch", "lineitem")) {
            while (rs.next()) {
                rs.getString("COLUMN_NAME");
                rs.getString("PK_NAME");
            }
        }
    }
}

