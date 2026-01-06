package com.databricks.jdbc.memoryharness.workload;

import com.databricks.jdbc.memoryharness.config.HarnessConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Workload that repeatedly opens and closes JDBC connections.
 * Executes a trivial query (SELECT 1) on each connection.
 * Configurable iterations and concurrency.
 */
public final class ConnectionLifecycleWorkload implements Workload {

    private static final String NAME = "connection-lifecycle";
    private static final String TRIVIAL_QUERY = "SELECT 1";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Repeatedly opens and closes JDBC connections with trivial queries";
    }

    @Override
    public WorkloadResult execute(HarnessConfig config) throws WorkloadException {
        int iterations = config.getConnectionLifecycleIterations();
        int concurrency = config.getConnectionLifecycleConcurrency();
        String jdbcUrl = config.getJdbcUrl();

        System.out.println("[" + NAME + "] Starting with " + iterations + " iterations, concurrency=" + concurrency);

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        if (concurrency <= 1) {
            // Sequential execution
            for (int i = 0; i < iterations; i++) {
                if (executeOneConnection(jdbcUrl, config)) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }
                if ((i + 1) % 10 == 0) {
                    System.out.println("[" + NAME + "] Progress: " + (i + 1) + "/" + iterations);
                }
            }
        } else {
            // Concurrent execution
            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            CountDownLatch latch = new CountDownLatch(iterations);

            for (int i = 0; i < iterations; i++) {
                final int iteration = i;
                executor.submit(() -> {
                    try {
                        if (executeOneConnection(jdbcUrl, config)) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                        long completed = iterations - latch.getCount();
                        if (completed % 10 == 0) {
                            System.out.println("[" + NAME + "] Progress: " + completed + "/" + iterations);
                        }
                    }
                });
            }

            try {
                latch.await(30, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkloadException("Workload interrupted", e);
            } finally {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                }
            }
        }

        long endTime = System.currentTimeMillis();

        System.out.println("[" + NAME + "] Completed: success=" + successCount.get() + ", failed=" + failCount.get());

        return WorkloadResult.builder(NAME)
                .startTimeMillis(startTime)
                .endTimeMillis(endTime)
                .totalOperations(iterations)
                .successfulOperations(successCount.get())
                .failedOperations(failCount.get())
                .build();
    }

    private boolean executeOneConnection(String jdbcUrl, HarnessConfig config) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.getConnectionProperties())) {
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(TRIVIAL_QUERY)) {
                    while (rs.next()) {
                        // Consume the result
                        rs.getInt(1);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            System.err.println("[" + NAME + "] Connection failed: " + e.getMessage());
            return false;
        }
    }
}

