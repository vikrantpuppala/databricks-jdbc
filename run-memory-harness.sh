#!/bin/bash
#
# Databricks JDBC Driver - Memory Regression Harness
#
# This script builds and runs the memory harness in a forked JVM with
# deterministic settings for memory benchmarking.
#
# Usage:
#   ./run-memory-harness.sh [options] [workloads...]
#
# Options:
#   --skip-build    Skip building the driver and harness
#   --heap=SIZE     Set heap size (default: 2g)
#   --output=DIR    Set output directory (default: memory-report)
#   --help          Show this help message
#
# Workloads:
#   connection-lifecycle  - Connection open/close stress test
#   metadata-heavy        - DatabaseMetaData operations
#   resultset-iteration   - ResultSet iteration workload
#   mixed-steady-state    - Long-running mixed workload
#   all                   - Run all workloads (default)
#
# Required environment variables:
#   HARNESS_JDBC_URL    - JDBC connection URL
#   HARNESS_USERNAME    - Database username (or 'token')
#   HARNESS_PASSWORD    - Database password or access token
#
# Example:
#   export HARNESS_JDBC_URL="jdbc:databricks://host:443/default;transportMode=http;ssl=1;AuthMech=3;httpPath=/sql/1.0/warehouses/xxx"
#   export HARNESS_USERNAME="token"
#   export HARNESS_PASSWORD="dapi..."
#   ./run-memory-harness.sh
#

set -e

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Defaults
HEAP_SIZE="2g"
SKIP_BUILD=false
OUTPUT_DIR="memory-report"
WORKLOADS=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --heap=*)
            HEAP_SIZE="${1#*=}"
            shift
            ;;
        --output=*)
            OUTPUT_DIR="${1#*=}"
            shift
            ;;
        --help|-h)
            head -n 35 "$0" | tail -n +2 | sed 's/^# //' | sed 's/^#//'
            exit 0
            ;;
        -*)
            echo "Unknown option: $1"
            exit 1
            ;;
        *)
            WORKLOADS="$WORKLOADS $1"
            shift
            ;;
    esac
done

# Validate required environment variables
if [[ -z "$HARNESS_JDBC_URL" ]]; then
    echo "ERROR: HARNESS_JDBC_URL environment variable not set"
    echo "Example: export HARNESS_JDBC_URL=\"jdbc:databricks://host:443/default;...\""
    exit 1
fi

if [[ -z "$HARNESS_USERNAME" ]]; then
    echo "ERROR: HARNESS_USERNAME environment variable not set"
    echo "Example: export HARNESS_USERNAME=\"token\""
    exit 1
fi

if [[ -z "$HARNESS_PASSWORD" ]]; then
    echo "ERROR: HARNESS_PASSWORD environment variable not set"
    echo "Example: export HARNESS_PASSWORD=\"dapi...\""
    exit 1
fi

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║     Databricks JDBC Driver - Memory Regression Harness       ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "Configuration:"
echo "  Heap Size:    $HEAP_SIZE"
echo "  Output Dir:   $OUTPUT_DIR"
echo "  Skip Build:   $SKIP_BUILD"
echo "  Workloads:    ${WORKLOADS:-all}"
echo ""

# Step 1: Build the driver (if not skipping)
if [[ "$SKIP_BUILD" != "true" ]]; then
    echo "=== Building JDBC Driver ==="
    mvn clean package -DskipTests -q
    echo "Driver built successfully"
    echo ""
    
    echo "=== Building Memory Harness ==="
    cd memory-harness
    mvn clean package -DskipTests -q
    cd ..
    echo "Harness built successfully"
    echo ""
fi

# Find the driver JAR
DRIVER_JAR=$(find target -name "databricks-jdbc-*.jar" -not -name "*-thin.jar" -not -name "*-tests.jar" 2>/dev/null | head -1)
if [[ -z "$DRIVER_JAR" ]]; then
    echo "ERROR: Could not find driver JAR in target/"
    echo "Run without --skip-build to build the driver first"
    exit 1
fi
echo "Using driver: $DRIVER_JAR"

# Find the harness JAR
HARNESS_JAR=$(find memory-harness/target -name "databricks-jdbc-memory-harness-*.jar" 2>/dev/null | head -1)
if [[ -z "$HARNESS_JAR" ]]; then
    echo "ERROR: Could not find harness JAR in memory-harness/target/"
    echo "Run without --skip-build to build the harness first"
    exit 1
fi
echo "Using harness: $HARNESS_JAR"

# Build classpath
CLASSPATH="$HARNESS_JAR:$DRIVER_JAR"

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Set output directory for harness
export HARNESS_OUTPUT_DIR="$OUTPUT_DIR"

# JVM flags for memory benchmarking
JVM_FLAGS=(
    # Fixed heap size for deterministic measurement
    "-Xms${HEAP_SIZE}"
    "-Xmx${HEAP_SIZE}"
    
    # Use G1GC for consistent behavior
    "-XX:+UseG1GC"
    
    # Enable Native Memory Tracking
    "-XX:NativeMemoryTracking=summary"
    
    # Enable diagnostic VM options
    "-XX:+UnlockDiagnosticVMOptions"
    
    # GC logging
    "-Xlog:gc*:file=${OUTPUT_DIR}/gc.log:time,uptime,level,tags"
    
    # Disable JIT compilation variability for more deterministic results
    "-XX:+UseCounterDecay"
    
    # Ensure consistent string deduplication behavior
    "-XX:+UseStringDeduplication"
    
    # Required for Arrow memory
    "--add-opens=java.base/java.nio=ALL-UNNAMED"
    
    # Classpath
    "-cp"
    "$CLASSPATH"
)

# Determine Java executable
if [[ -n "$JAVA_HOME" ]]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi

# Print Java version
echo ""
echo "Java version:"
"$JAVA" -version 2>&1 | head -3
echo ""

# Run the harness
echo "=== Starting Memory Harness ==="
echo "JVM flags: ${JVM_FLAGS[*]}"
echo ""

# Execute the harness
"$JAVA" "${JVM_FLAGS[@]}" \
    com.databricks.jdbc.memoryharness.MemoryHarnessMain \
    $WORKLOADS

# Check exit code
EXIT_CODE=$?
if [[ $EXIT_CODE -ne 0 ]]; then
    echo ""
    echo "ERROR: Memory harness failed with exit code $EXIT_CODE"
    exit $EXIT_CODE
fi

# Print report location
echo ""
echo "=== Reports Generated ==="
echo "Location: $OUTPUT_DIR/"
ls -la "$OUTPUT_DIR/"
echo ""
echo "To view summary: cat $OUTPUT_DIR/summary.json"
echo ""

