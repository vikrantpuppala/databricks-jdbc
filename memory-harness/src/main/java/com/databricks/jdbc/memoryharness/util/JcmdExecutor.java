package com.databricks.jdbc.memoryharness.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Utility class for executing jcmd commands against the current JVM.
 * Used to collect memory metrics via JDK tooling.
 */
public final class JcmdExecutor {

    private static final long TIMEOUT_SECONDS = 60;

    private JcmdExecutor() {
        // Utility class
    }

    /**
     * Executes a jcmd command and returns the output as a list of lines.
     *
     * @param pid the target process ID
     * @param command the jcmd command (e.g., "GC.heap_info", "VM.native_memory")
     * @return list of output lines
     * @throws JcmdException if the command fails
     */
    public static List<String> execute(long pid, String command) throws JcmdException {
        String jcmdPath = findJcmd();
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jcmdPath);
        cmdLine.add(String.valueOf(pid));
        cmdLine.add(command);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<String> output = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new JcmdException("jcmd timed out after " + TIMEOUT_SECONDS + " seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new JcmdException("jcmd exited with code " + exitCode + ": " + String.join("\n", output));
            }

            return output;

        } catch (IOException | InterruptedException e) {
            throw new JcmdException("Failed to execute jcmd: " + e.getMessage(), e);
        }
    }

    /**
     * Triggers a full GC via jcmd.
     */
    public static void triggerFullGc(long pid) throws JcmdException {
        execute(pid, "GC.run");
    }

    /**
     * Gets heap info after GC.
     */
    public static List<String> getHeapInfo(long pid) throws JcmdException {
        return execute(pid, "GC.heap_info");
    }

    /**
     * Gets native memory summary.
     * Requires -XX:NativeMemoryTracking=summary to be enabled.
     */
    public static List<String> getNativeMemorySummary(long pid) throws JcmdException {
        return execute(pid, "VM.native_memory summary");
    }

    /**
     * Gets VM flags.
     */
    public static List<String> getVmFlags(long pid) throws JcmdException {
        return execute(pid, "VM.flags");
    }

    /**
     * Gets VM system properties.
     */
    public static List<String> getSystemProperties(long pid) throws JcmdException {
        return execute(pid, "VM.system_properties");
    }

    /**
     * Finds the jcmd executable.
     * Looks in JAVA_HOME/bin first, then falls back to PATH.
     */
    private static String findJcmd() {
        // Try JAVA_HOME first
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            // java.home points to JRE, go up to JDK if needed
            String[] possiblePaths = {
                    javaHome + "/bin/jcmd",
                    javaHome + "/../bin/jcmd",  // java.home is JRE inside JDK
            };

            for (String path : possiblePaths) {
                java.io.File file = new java.io.File(path);
                if (file.exists() && file.canExecute()) {
                    return file.getAbsolutePath();
                }
            }
        }

        // Fall back to PATH
        return "jcmd";
    }

    /**
     * Checks if jcmd is available.
     */
    public static boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(findJcmd(), "-h");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Exception thrown when jcmd execution fails.
     */
    public static class JcmdException extends Exception {
        public JcmdException(String message) {
            super(message);
        }

        public JcmdException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

