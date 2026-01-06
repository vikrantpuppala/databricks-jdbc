package com.databricks.jdbc.memoryharness.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for process-related operations.
 * Provides methods for getting PID, reading /proc on Linux, etc.
 */
public final class ProcessUtils {

    private ProcessUtils() {
        // Utility class
    }

    /**
     * Gets the PID of the current JVM process.
     * Works on Java 9+ directly; falls back to parsing RuntimeMXBean name.
     */
    public static long getCurrentPid() {
        // Java 9+ has ProcessHandle.current().pid()
        try {
            return ProcessHandle.current().pid();
        } catch (Exception e) {
            // Fallback for older approach
            String name = ManagementFactory.getRuntimeMXBean().getName();
            // Format: pid@hostname
            int atIndex = name.indexOf('@');
            if (atIndex > 0) {
                return Long.parseLong(name.substring(0, atIndex));
            }
            throw new RuntimeException("Could not determine PID", e);
        }
    }

    /**
     * Reads RSS (Resident Set Size) from /proc/<pid>/status on Linux.
     * Returns RSS in bytes.
     *
     * @param pid the process ID
     * @return RSS in bytes, or -1 if not available (non-Linux systems)
     */
    public static long readRssBytes(long pid) {
        File statusFile = new File("/proc/" + pid + "/status");
        if (!statusFile.exists()) {
            // Not on Linux or /proc not available
            return readRssViaPs(pid);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    // Format: "VmRSS:     12345 kB"
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        long kbValue = Long.parseLong(parts[1]);
                        return kbValue * 1024; // Convert to bytes
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("Warning: Could not read RSS from /proc: " + e.getMessage());
        }

        return readRssViaPs(pid);
    }

    /**
     * Fallback method to read RSS via 'ps' command (works on macOS and Linux).
     */
    private static long readRssViaPs(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    // ps reports RSS in kilobytes
                    long kbValue = Long.parseLong(line.trim());
                    return kbValue * 1024; // Convert to bytes
                }
            }

            process.waitFor();
        } catch (Exception e) {
            System.err.println("Warning: Could not read RSS via ps: " + e.getMessage());
        }

        return -1;
    }

    /**
     * Reads memory info from /proc/<pid>/status on Linux.
     * Returns a map with various memory metrics.
     */
    public static Map<String, Long> readProcStatus(long pid) {
        Map<String, Long> result = new HashMap<>();
        File statusFile = new File("/proc/" + pid + "/status");

        if (!statusFile.exists()) {
            return result;
        }

        Pattern pattern = Pattern.compile("^(Vm\\w+|Rss\\w*):\\s+(\\d+)\\s+kB");

        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.matches()) {
                    String key = matcher.group(1);
                    long kbValue = Long.parseLong(matcher.group(2));
                    result.put(key, kbValue * 1024); // Store in bytes
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not read /proc/status: " + e.getMessage());
        }

        return result;
    }

    /**
     * Reads peak RSS from /proc/<pid>/status (VmHWM on Linux).
     */
    public static long readPeakRssBytes(long pid) {
        File statusFile = new File("/proc/" + pid + "/status");

        if (!statusFile.exists()) {
            // Not on Linux, return current RSS as best estimate
            return readRssBytes(pid);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmHWM:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        long kbValue = Long.parseLong(parts[1]);
                        return kbValue * 1024;
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("Warning: Could not read peak RSS: " + e.getMessage());
        }

        return readRssBytes(pid);
    }

    /**
     * Gets the JVM version information.
     */
    public static String getJvmVersion() {
        return System.getProperty("java.version") + " (" +
                System.getProperty("java.vendor") + ", " +
                System.getProperty("java.vm.name") + ")";
    }

    /**
     * Gets the OS information.
     */
    public static String getOsInfo() {
        return System.getProperty("os.name") + " " +
                System.getProperty("os.version") + " " +
                System.getProperty("os.arch");
    }
}

