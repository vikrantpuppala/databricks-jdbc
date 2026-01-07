package com.databricks.jdbc.memoryharness.metrics;

/**
 * Utility class to track peak heap usage during workload execution.
 * Designed to capture maximum heap usage during result set iteration,
 * which is critical for measuring memory optimizations like ColumnarRowView.
 *
 * <p>Usage:
 * <pre>
 * PeakHeapTracker.getInstance().reset();
 * while (rs.next()) {
 *     // process row
 *     if (rowCount % 10000 == 0) {
 *         PeakHeapTracker.getInstance().sample();
 *     }
 * }
 * long peakBytes = PeakHeapTracker.getInstance().getPeakBytes();
 * </pre>
 */
public final class PeakHeapTracker {

    private static final PeakHeapTracker INSTANCE = new PeakHeapTracker();

    private volatile long peakHeapBytes = 0;
    private volatile long sampleCount = 0;
    private volatile long lastSampleTimeMs = 0;

    private PeakHeapTracker() {
        // Singleton
    }

    /**
     * Returns the singleton instance.
     */
    public static PeakHeapTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Samples the current heap usage and updates peak if higher.
     * Uses Runtime to get current heap consumption.
     *
     * @return the current heap usage in bytes
     */
    public long sample() {
        Runtime runtime = Runtime.getRuntime();
        long currentHeap = runtime.totalMemory() - runtime.freeMemory();
        
        synchronized (this) {
            sampleCount++;
            lastSampleTimeMs = System.currentTimeMillis();
            if (currentHeap > peakHeapBytes) {
                peakHeapBytes = currentHeap;
            }
        }
        
        return currentHeap;
    }

    /**
     * Returns the peak heap usage observed since last reset.
     *
     * @return peak heap in bytes
     */
    public synchronized long getPeakBytes() {
        return peakHeapBytes;
    }

    /**
     * Returns the number of samples taken since last reset.
     *
     * @return sample count
     */
    public synchronized long getSampleCount() {
        return sampleCount;
    }

    /**
     * Returns the timestamp of the last sample.
     *
     * @return timestamp in milliseconds since epoch
     */
    public synchronized long getLastSampleTimeMs() {
        return lastSampleTimeMs;
    }

    /**
     * Resets the peak tracker to start fresh measurement.
     */
    public synchronized void reset() {
        peakHeapBytes = 0;
        sampleCount = 0;
        lastSampleTimeMs = 0;
    }

    /**
     * Formats bytes as a human-readable string.
     *
     * @param bytes the byte count
     * @return formatted string (e.g., "745.2 MB")
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public String toString() {
        return String.format("PeakHeapTracker{peak=%s, samples=%d}",
                formatBytes(peakHeapBytes), sampleCount);
    }
}

