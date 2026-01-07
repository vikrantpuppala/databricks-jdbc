package com.databricks.jdbc.memoryharness.workload;

import com.databricks.jdbc.memoryharness.config.HarnessConfig;
import com.databricks.jdbc.memoryharness.metrics.PeakHeapTracker;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Workload that processes large result sets (500K-5M rows) to benchmark
 * memory efficiency during result set iteration.
 *
 * <p>This workload is specifically designed to measure the memory improvements
 * from optimizations like ColumnarRowView (PR #975), which reduced peak heap
 * from 8,440 MB to 745 MB when processing 5 million rows.
 *
 * <p>Key features:
 * <ul>
 *   <li>Configurable row count via HARNESS_RS_LARGE_ROWS (default: 500,000)</li>
 *   <li>Configurable iterations via HARNESS_RS_LARGE_ITERATIONS (default: 1)</li>
 *   <li>Samples heap usage every 10,000 rows to track peak memory</li>
 *   <li>Reads multiple column types to exercise deserialization</li>
 * </ul>
 *
 * <p>Configuration environment variables:
 * <ul>
 *   <li>HARNESS_RS_LARGE_ROWS - Number of rows to generate (default: 500000)</li>
 *   <li>HARNESS_RS_LARGE_ITERATIONS - Number of iterations (default: 1)</li>
 * </ul>
 */
public final class LargeResultSetWorkload implements Workload {

    private static final String NAME = "large-resultset";

    /** Sample heap every N rows during iteration */
    private static final int HEAP_SAMPLE_INTERVAL = 10_000;

    /** Progress report interval */
    private static final int PROGRESS_REPORT_INTERVAL = 100_000;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Processes large result sets (500K-5M rows) with peak heap tracking";
    }

    @Override
    public WorkloadResult execute(HarnessConfig config) throws WorkloadException {
        int iterations = config.getResultSetLargeIterations();
        int rowCount = config.getResultSetLargeRowCount();
        String jdbcUrl = config.getJdbcUrl();

        System.out.println("[" + NAME + "] Starting with " + iterations +
                " iterations, " + rowCount + " rows each");
        System.out.println("[" + NAME + "] Heap sampling every " + HEAP_SAMPLE_INTERVAL + " rows");

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicLong totalRowsProcessed = new AtomicLong(0);

        // Reset peak tracker before workload
        PeakHeapTracker peakTracker = PeakHeapTracker.getInstance();
        peakTracker.reset();

        // Sample baseline before starting
        long baselineHeap = peakTracker.sample();
        System.out.println("[" + NAME + "] Baseline heap: " + PeakHeapTracker.formatBytes(baselineHeap));

        long startTime = System.currentTimeMillis();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.getConnectionProperties())) {
            
            for (int i = 0; i < iterations; i++) {
                System.out.println("[" + NAME + "] Iteration " + (i + 1) + "/" + iterations);

                try {
                    long rows = executeLargeQuery(conn, rowCount, peakTracker);
                    totalRowsProcessed.addAndGet(rows);
                    successCount.incrementAndGet();

                    System.out.println("[" + NAME + "] Iteration " + (i + 1) + " complete: " +
                            rows + " rows processed, peak heap so far: " +
                            PeakHeapTracker.formatBytes(peakTracker.getPeakBytes()));

                } catch (Exception e) {
                    System.err.println("[" + NAME + "] Iteration " + (i + 1) + " failed: " + e.getMessage());
                    e.printStackTrace();
                    failCount.incrementAndGet();
                }
            }

        } catch (Exception e) {
            throw new WorkloadException("Failed to establish connection for large result set workload", e);
        }

        long endTime = System.currentTimeMillis();
        long peakHeapBytes = peakTracker.getPeakBytes();

        System.out.println("[" + NAME + "] Completed: total iterations=" + iterations +
                ", rows processed=" + totalRowsProcessed.get() +
                " (success=" + successCount.get() + ", failed=" + failCount.get() + ")");
        System.out.println("[" + NAME + "] Peak heap during workload: " +
                PeakHeapTracker.formatBytes(peakHeapBytes) +
                " (" + peakHeapBytes + " bytes)");
        System.out.println("[" + NAME + "] Heap samples taken: " + peakTracker.getSampleCount());

        return WorkloadResult.builder(NAME)
                .startTimeMillis(startTime)
                .endTimeMillis(endTime)
                .totalOperations(iterations)
                .successfulOperations(successCount.get())
                .failedOperations(failCount.get())
                .peakHeapBytes(peakHeapBytes)
                .rowsProcessed(totalRowsProcessed.get())
                .build();
    }

    /**
     * Executes a query that generates a large result set and iterates through all rows.
     * Samples heap usage at regular intervals to track peak memory consumption.
     *
     * @param conn     the database connection
     * @param rowCount number of rows to generate
     * @param tracker  the peak heap tracker for sampling
     * @return the number of rows processed
     */
    private long executeLargeQuery(Connection conn, int rowCount, PeakHeapTracker tracker) throws Exception {
        // Generate rows using Databricks SQL functions
        // SEQUENCE generates an array, EXPLODE expands it to rows
        // Query includes multiple column types to exercise deserialization
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

        System.out.println("[" + NAME + "] Executing query for " + rowCount + " rows...");

        long count = 0;
        long queryStartTime = System.currentTimeMillis();

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            long fetchStartTime = System.currentTimeMillis();
            System.out.println("[" + NAME + "] Query executed in " +
                    (fetchStartTime - queryStartTime) + "ms, starting iteration...");

            while (rs.next()) {
                // Read all column types to exercise deserialization
                int intVal = rs.getInt("int_col");
                String strVal = rs.getString("str_col");
                double doubleVal = rs.getDouble("double_col");
                boolean boolVal = rs.getBoolean("bool_col");
                Timestamp tsVal = rs.getTimestamp("ts_col");
                
                count++;

                // Sample heap at regular intervals
                if (count % HEAP_SAMPLE_INTERVAL == 0) {
                    long currentHeap = tracker.sample();

                    // Report progress at larger intervals
                    if (count % PROGRESS_REPORT_INTERVAL == 0) {
                        long elapsed = System.currentTimeMillis() - fetchStartTime;
                        double rowsPerSec = (count * 1000.0) / elapsed;
                        System.out.println("[" + NAME + "] Progress: " + count + "/" + rowCount +
                                " rows (" + String.format("%.1f", (count * 100.0 / rowCount)) + "%)" +
                                ", current heap: " + PeakHeapTracker.formatBytes(currentHeap) +
                                ", peak: " + PeakHeapTracker.formatBytes(tracker.getPeakBytes()) +
                                ", rate: " + String.format("%.0f", rowsPerSec) + " rows/sec");
                    }
                }
            }

            // Final sample after iteration complete
            tracker.sample();

            long totalTime = System.currentTimeMillis() - queryStartTime;
            long fetchTime = System.currentTimeMillis() - fetchStartTime;
            double rowsPerSec = (count * 1000.0) / fetchTime;

            System.out.println("[" + NAME + "] Iteration complete: " + count + " rows in " +
                    totalTime + "ms (fetch: " + fetchTime + "ms, " +
                    String.format("%.0f", rowsPerSec) + " rows/sec)");
        }

        return count;
    }
}

