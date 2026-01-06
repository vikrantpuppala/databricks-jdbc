package com.databricks.jdbc.memoryharness.config;

import java.util.Properties;

/**
 * Configuration holder for the memory harness.
 * All configuration is read from environment variables or system properties.
 */
public final class HarnessConfig {

    // Connection settings (required)
    private final String jdbcUrl;
    private final String username;
    private final String password;

    // Workload settings
    private final int connectionLifecycleIterations;
    private final int connectionLifecycleConcurrency;
    private final int metadataIterations;
    private final int resultSetSmallIterations;
    private final int resultSetMediumIterations;
    private final int resultSetMediumRowCount;
    private final long mixedWorkloadDurationSeconds;

    // Output settings
    private final String outputDirectory;

    private HarnessConfig(Builder builder) {
        this.jdbcUrl = builder.jdbcUrl;
        this.username = builder.username;
        this.password = builder.password;
        this.connectionLifecycleIterations = builder.connectionLifecycleIterations;
        this.connectionLifecycleConcurrency = builder.connectionLifecycleConcurrency;
        this.metadataIterations = builder.metadataIterations;
        this.resultSetSmallIterations = builder.resultSetSmallIterations;
        this.resultSetMediumIterations = builder.resultSetMediumIterations;
        this.resultSetMediumRowCount = builder.resultSetMediumRowCount;
        this.mixedWorkloadDurationSeconds = builder.mixedWorkloadDurationSeconds;
        this.outputDirectory = builder.outputDirectory;
    }

    /**
     * Creates a configuration from environment variables.
     * Required environment variables:
     * - HARNESS_JDBC_URL: JDBC connection URL
     * - HARNESS_USERNAME: Database username (or 'token' for PAT auth)
     * - HARNESS_PASSWORD: Database password or personal access token
     *
     * Optional environment variables:
     * - HARNESS_CONN_ITERATIONS: Connection lifecycle iterations (default: 100)
     * - HARNESS_CONN_CONCURRENCY: Connection lifecycle concurrency (default: 4)
     * - HARNESS_METADATA_ITERATIONS: Metadata workload iterations (default: 50)
     * - HARNESS_RS_SMALL_ITERATIONS: Small result set iterations (default: 100)
     * - HARNESS_RS_MEDIUM_ITERATIONS: Medium result set iterations (default: 20)
     * - HARNESS_RS_MEDIUM_ROWS: Medium result set row count (default: 5000)
     * - HARNESS_MIXED_DURATION_SECONDS: Mixed workload duration in seconds (default: 120)
     * - HARNESS_OUTPUT_DIR: Output directory (default: memory-report)
     */
    public static HarnessConfig fromEnvironment() {
        String jdbcUrl = requireEnv("HARNESS_JDBC_URL");
        String username = requireEnv("HARNESS_USERNAME");
        String password = requireEnv("HARNESS_PASSWORD");

        return new Builder()
                .jdbcUrl(jdbcUrl)
                .username(username)
                .password(password)
                .connectionLifecycleIterations(getEnvInt("HARNESS_CONN_ITERATIONS", 100))
                .connectionLifecycleConcurrency(getEnvInt("HARNESS_CONN_CONCURRENCY", 4))
                .metadataIterations(getEnvInt("HARNESS_METADATA_ITERATIONS", 50))
                .resultSetSmallIterations(getEnvInt("HARNESS_RS_SMALL_ITERATIONS", 100))
                .resultSetMediumIterations(getEnvInt("HARNESS_RS_MEDIUM_ITERATIONS", 20))
                .resultSetMediumRowCount(getEnvInt("HARNESS_RS_MEDIUM_ROWS", 5000))
                .mixedWorkloadDurationSeconds(getEnvLong("HARNESS_MIXED_DURATION_SECONDS", 120))
                .outputDirectory(getEnvString("HARNESS_OUTPUT_DIR", "memory-report"))
                .build();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value;
    }

    private static String getEnvString(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    private static int getEnvInt(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Warning: Invalid integer for " + name + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    private static long getEnvLong(String name, long defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            System.err.println("Warning: Invalid long for " + name + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    // Getters

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public Properties getConnectionProperties() {
        Properties props = new Properties();
        props.setProperty("uid", username);
        props.setProperty("password", password);
        return props;
    }

    public int getConnectionLifecycleIterations() {
        return connectionLifecycleIterations;
    }

    public int getConnectionLifecycleConcurrency() {
        return connectionLifecycleConcurrency;
    }

    public int getMetadataIterations() {
        return metadataIterations;
    }

    public int getResultSetSmallIterations() {
        return resultSetSmallIterations;
    }

    public int getResultSetMediumIterations() {
        return resultSetMediumIterations;
    }

    public int getResultSetMediumRowCount() {
        return resultSetMediumRowCount;
    }

    public long getMixedWorkloadDurationSeconds() {
        return mixedWorkloadDurationSeconds;
    }

    public String getOutputDirectory() {
        return outputDirectory;
    }

    @Override
    public String toString() {
        return "HarnessConfig{" +
                "jdbcUrl='" + maskPassword(jdbcUrl) + '\'' +
                ", username='" + username + '\'' +
                ", connectionLifecycleIterations=" + connectionLifecycleIterations +
                ", connectionLifecycleConcurrency=" + connectionLifecycleConcurrency +
                ", metadataIterations=" + metadataIterations +
                ", resultSetSmallIterations=" + resultSetSmallIterations +
                ", resultSetMediumIterations=" + resultSetMediumIterations +
                ", resultSetMediumRowCount=" + resultSetMediumRowCount +
                ", mixedWorkloadDurationSeconds=" + mixedWorkloadDurationSeconds +
                ", outputDirectory='" + outputDirectory + '\'' +
                '}';
    }

    private String maskPassword(String url) {
        // Mask any password in the URL
        return url.replaceAll("password=[^;]+", "password=***");
    }

    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private int connectionLifecycleIterations = 100;
        private int connectionLifecycleConcurrency = 4;
        private int metadataIterations = 50;
        private int resultSetSmallIterations = 100;
        private int resultSetMediumIterations = 20;
        private int resultSetMediumRowCount = 5000;
        private long mixedWorkloadDurationSeconds = 120;
        private String outputDirectory = "memory-report";

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder connectionLifecycleIterations(int iterations) {
            this.connectionLifecycleIterations = iterations;
            return this;
        }

        public Builder connectionLifecycleConcurrency(int concurrency) {
            this.connectionLifecycleConcurrency = concurrency;
            return this;
        }

        public Builder metadataIterations(int iterations) {
            this.metadataIterations = iterations;
            return this;
        }

        public Builder resultSetSmallIterations(int iterations) {
            this.resultSetSmallIterations = iterations;
            return this;
        }

        public Builder resultSetMediumIterations(int iterations) {
            this.resultSetMediumIterations = iterations;
            return this;
        }

        public Builder resultSetMediumRowCount(int rowCount) {
            this.resultSetMediumRowCount = rowCount;
            return this;
        }

        public Builder mixedWorkloadDurationSeconds(long seconds) {
            this.mixedWorkloadDurationSeconds = seconds;
            return this;
        }

        public Builder outputDirectory(String outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public HarnessConfig build() {
            if (jdbcUrl == null || jdbcUrl.isEmpty()) {
                throw new IllegalStateException("jdbcUrl is required");
            }
            if (username == null || username.isEmpty()) {
                throw new IllegalStateException("username is required");
            }
            if (password == null || password.isEmpty()) {
                throw new IllegalStateException("password is required");
            }
            return new HarnessConfig(this);
        }
    }
}

