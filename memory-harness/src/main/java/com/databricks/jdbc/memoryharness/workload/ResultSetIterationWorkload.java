package com.databricks.jdbc.memoryharness.workload;

import com.databricks.jdbc.memoryharness.config.HarnessConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Workload that iterates over result sets of varying sizes.
 * - Small result set (SELECT 1)
 * - Medium result set (1k-10k rows)
 * Fully iterates and reads multiple column types.
 */
public final class ResultSetIterationWorkload implements Workload {

    private static final String NAME = "resultset-iteration";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Iterates over small and medium result sets, reading multiple column types";
    }

    @Override
    public WorkloadResult execute(HarnessConfig config) throws WorkloadException {
        int smallIterations = config.getResultSetSmallIterations();
        int mediumIterations = config.getResultSetMediumIterations();
        int mediumRowCount = config.getResultSetMediumRowCount();
        String jdbcUrl = config.getJdbcUrl();

        System.out.println("[" + NAME + "] Starting with small=" + smallIterations +
                " iterations, medium=" + mediumIterations + " iterations (" + mediumRowCount + " rows each)");

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicLong rowsProcessed = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.getConnectionProperties())) {

            // Phase 1: Small result set iterations
            System.out.println("[" + NAME + "] Phase 1: Small result sets");
            for (int i = 0; i < smallIterations; i++) {
                try {
                    long rows = executeSmallQuery(conn);
                    rowsProcessed.addAndGet(rows);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("[" + NAME + "] Small query " + i + " failed: " + e.getMessage());
                    failCount.incrementAndGet();
                }

                if ((i + 1) % 20 == 0) {
                    System.out.println("[" + NAME + "] Small progress: " + (i + 1) + "/" + smallIterations);
                }
            }

            // Phase 2: Medium result set iterations
            System.out.println("[" + NAME + "] Phase 2: Medium result sets (" + mediumRowCount + " rows)");
            for (int i = 0; i < mediumIterations; i++) {
                try {
                    long rows = executeMediumQuery(conn, mediumRowCount);
                    rowsProcessed.addAndGet(rows);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("[" + NAME + "] Medium query " + i + " failed: " + e.getMessage());
                    failCount.incrementAndGet();
                }

                if ((i + 1) % 5 == 0) {
                    System.out.println("[" + NAME + "] Medium progress: " + (i + 1) + "/" + mediumIterations);
                }
            }

        } catch (Exception e) {
            throw new WorkloadException("Failed to establish connection for result set workload", e);
        }

        long endTime = System.currentTimeMillis();

        long totalOps = smallIterations + mediumIterations;

        System.out.println("[" + NAME + "] Completed: total queries=" + totalOps +
                ", rows processed=" + rowsProcessed.get() +
                " (success=" + successCount.get() + ", failed=" + failCount.get() + ")");

        return WorkloadResult.builder(NAME)
                .startTimeMillis(startTime)
                .endTimeMillis(endTime)
                .totalOperations(totalOps)
                .successfulOperations(successCount.get())
                .failedOperations(failCount.get())
                .build();
    }

    /**
     * Executes a trivial single-row query.
     */
    private long executeSmallQuery(Connection conn) throws Exception {
        // Query with multiple column types
        String sql = "SELECT 1 AS int_col, 'hello' AS str_col, 3.14159 AS double_col, true AS bool_col";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            long count = 0;
            while (rs.next()) {
                // Read all column types
                rs.getInt("int_col");
                rs.getString("str_col");
                rs.getDouble("double_col");
                rs.getBoolean("bool_col");
                count++;
            }
            return count;
        }
    }

    /**
     * Executes a query that generates a medium-sized result set.
     * Uses EXPLODE with SEQUENCE to generate the specified number of rows.
     */
    private long executeMediumQuery(Connection conn, int rowCount) throws Exception {
        // Generate rows using Databricks SQL functions
        // SEQUENCE generates an array, EXPLODE expands it to rows
        String sql = String.format(
                "SELECT " +
                "  id AS int_col, " +
                "  CONCAT('row_', CAST(id AS STRING)) AS str_col, " +
                "  CAST(id AS DOUBLE) * 1.5 AS double_col, " +
                "  (id %% 2 = 0) AS bool_col, " +
                "  CURRENT_TIMESTAMP() AS ts_col " +
                "FROM (SELECT EXPLODE(SEQUENCE(1, %d)) AS id)",
                rowCount
        );

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            long count = 0;
            while (rs.next()) {
                // Read all column types to exercise deserialization
                rs.getInt("int_col");
                rs.getString("str_col");
                rs.getDouble("double_col");
                rs.getBoolean("bool_col");
                rs.getTimestamp("ts_col");
                count++;
            }
            return count;
        }
    }
}

