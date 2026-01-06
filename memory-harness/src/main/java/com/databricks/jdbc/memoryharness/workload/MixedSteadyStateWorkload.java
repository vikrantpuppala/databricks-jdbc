package com.databricks.jdbc.memoryharness.workload;

import com.databricks.jdbc.memoryharness.config.HarnessConfig;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Long-running mixed workload that simulates steady-state usage.
 * Mix of:
 * - Small queries
 * - Metadata calls
 * - Medium result queries
 *
 * Runs for a configurable duration.
 * Operations are cycled in a deterministic pattern.
 */
public final class MixedSteadyStateWorkload implements Workload {

    private static final String NAME = "mixed-steady-state";

    // Operation weights for deterministic cycling
    private static final int SMALL_QUERY_WEIGHT = 5;     // 5 small queries
    private static final int METADATA_WEIGHT = 2;         // 2 metadata calls
    private static final int MEDIUM_QUERY_WEIGHT = 1;     // 1 medium query
    private static final int CYCLE_LENGTH = SMALL_QUERY_WEIGHT + METADATA_WEIGHT + MEDIUM_QUERY_WEIGHT;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Long-running mixed workload with small queries, metadata calls, and medium result queries";
    }

    @Override
    public WorkloadResult execute(HarnessConfig config) throws WorkloadException {
        long durationSeconds = config.getMixedWorkloadDurationSeconds();
        String jdbcUrl = config.getJdbcUrl();

        System.out.println("[" + NAME + "] Starting for " + durationSeconds + " seconds");

        AtomicLong smallQueryCount = new AtomicLong(0);
        AtomicLong metadataCount = new AtomicLong(0);
        AtomicLong mediumQueryCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);

        long startTime = System.currentTimeMillis();
        long endTimeTarget = startTime + TimeUnit.SECONDS.toMillis(durationSeconds);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.getConnectionProperties())) {
            DatabaseMetaData metaData = conn.getMetaData();

            int operationIndex = 0;
            long lastProgressReport = startTime;

            while (System.currentTimeMillis() < endTimeTarget) {
                int cyclePosition = operationIndex % CYCLE_LENGTH;

                try {
                    if (cyclePosition < SMALL_QUERY_WEIGHT) {
                        // Small query
                        executeSmallQuery(conn);
                        smallQueryCount.incrementAndGet();
                    } else if (cyclePosition < SMALL_QUERY_WEIGHT + METADATA_WEIGHT) {
                        // Metadata call
                        executeMetadataCall(metaData, cyclePosition - SMALL_QUERY_WEIGHT);
                        metadataCount.incrementAndGet();
                    } else {
                        // Medium query
                        executeMediumQuery(conn, 1000);
                        mediumQueryCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    // Log sparingly to avoid flooding
                    if (failCount.get() <= 5) {
                        System.err.println("[" + NAME + "] Operation failed: " + e.getMessage());
                    }
                }

                operationIndex++;

                // Progress report every 30 seconds
                long now = System.currentTimeMillis();
                if (now - lastProgressReport >= 30_000) {
                    long elapsed = (now - startTime) / 1000;
                    long remaining = (endTimeTarget - now) / 1000;
                    System.out.println(String.format(
                            "[%s] Progress: %ds elapsed, %ds remaining | small=%d, metadata=%d, medium=%d, failed=%d",
                            NAME, elapsed, remaining,
                            smallQueryCount.get(), metadataCount.get(), mediumQueryCount.get(), failCount.get()
                    ));
                    lastProgressReport = now;
                }
            }

        } catch (Exception e) {
            throw new WorkloadException("Failed to establish connection for mixed workload", e);
        }

        long endTime = System.currentTimeMillis();
        long totalOps = smallQueryCount.get() + metadataCount.get() + mediumQueryCount.get();

        System.out.println(String.format(
                "[%s] Completed: total=%d (small=%d, metadata=%d, medium=%d), failed=%d, duration=%dms",
                NAME, totalOps, smallQueryCount.get(), metadataCount.get(), mediumQueryCount.get(),
                failCount.get(), (endTime - startTime)
        ));

        return WorkloadResult.builder(NAME)
                .startTimeMillis(startTime)
                .endTimeMillis(endTime)
                .totalOperations(totalOps)
                .successfulOperations(totalOps - failCount.get())
                .failedOperations(failCount.get())
                .build();
    }

    private void executeSmallQuery(Connection conn) throws Exception {
        String sql = "SELECT 1 AS val, 'test' AS str, 2.5 AS dbl";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                rs.getInt("val");
                rs.getString("str");
                rs.getDouble("dbl");
            }
        }
    }

    private void executeMetadataCall(DatabaseMetaData metaData, int variant) throws Exception {
        // Alternate between different metadata calls
        if (variant == 0) {
            try (ResultSet rs = metaData.getSchemas("main", "%")) {
                while (rs.next()) {
                    rs.getString("TABLE_SCHEM");
                }
            }
        } else {
            try (ResultSet rs = metaData.getTables("main", "default", "%", null)) {
                while (rs.next()) {
                    rs.getString("TABLE_NAME");
                }
            }
        }
    }

    private void executeMediumQuery(Connection conn, int rowCount) throws Exception {
        String sql = String.format(
                "SELECT id, CONCAT('row_', CAST(id AS STRING)) AS name " +
                "FROM (SELECT EXPLODE(SEQUENCE(1, %d)) AS id)",
                rowCount
        );
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                rs.getInt("id");
                rs.getString("name");
            }
        }
    }
}

