#!/bin/bash
#
# Databricks JDBC Driver - Run Memory Harness Across All Released Versions
#
# This script downloads driver JARs from Maven Central and runs the memory
# harness against each version for comparison.
#
# Usage:
#   ./run-memory-harness-all-versions.sh [options]
#
# Options:
#   --versions=LIST   Comma-separated list of versions to test (default: recent versions)
#   --output=DIR      Base output directory (default: memory-reports)
#   --heap=SIZE       Set heap size (default: 2g)
#   --continue        Resume from last completed version
#   --workloads=LIST  Comma-separated workloads to run (default: all)
#   --list-versions   List available versions from Maven and exit
#   --help            Show this help message
#
# Required environment variables:
#   HARNESS_JDBC_URL    - JDBC connection URL
#   HARNESS_USERNAME    - Database username (or 'token')
#   HARNESS_PASSWORD    - Database password or access token
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MAVEN_REPO="https://repo1.maven.org/maven2"
OUTPUT_BASE="memory-reports"
HEAP_SIZE="2g"
VERSIONS=""
CONTINUE_MODE=false
WORKLOADS="all"
LIST_VERSIONS=false
DEFAULT_VERSIONS="1.0.9-oss,1.0.10-oss,1.0.11-oss,3.0.1,3.0.3,3.0.4,3.0.5,3.0.6,3.0.7"

while [[ $# -gt 0 ]]; do
    case $1 in
        --versions=*) VERSIONS="${1#*=}"; shift ;;
        --output=*) OUTPUT_BASE="${1#*=}"; shift ;;
        --heap=*) HEAP_SIZE="${1#*=}"; shift ;;
        --continue) CONTINUE_MODE=true; shift ;;
        --workloads=*) WORKLOADS="${1#*=}"; shift ;;
        --list-versions) LIST_VERSIONS=true; shift ;;
        --help|-h) head -n 25 "$0" | tail -n +2 | sed 's/^# //' | sed 's/^#//'; exit 0 ;;
        -*) echo "Unknown option: $1"; exit 1 ;;
        *) shift ;;
    esac
done

list_maven_versions() {
    curl -s "${MAVEN_REPO}/com/databricks/databricks-jdbc/maven-metadata.xml" | grep "<version>" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | sort -V
}

if [[ "$LIST_VERSIONS" == "true" ]]; then list_maven_versions; exit 0; fi

[[ -z "$HARNESS_JDBC_URL" ]] && { echo "ERROR: HARNESS_JDBC_URL not set"; exit 1; }
[[ -z "$HARNESS_USERNAME" ]] && { echo "ERROR: HARNESS_USERNAME not set"; exit 1; }
[[ -z "$HARNESS_PASSWORD" ]] && { echo "ERROR: HARNESS_PASSWORD not set"; exit 1; }

[[ -z "$VERSIONS" ]] && VERSIONS="$DEFAULT_VERSIONS"

echo "╔══════════════════════════════════════════════════════════════════════╗"
echo "║  Databricks JDBC Driver - Memory Harness Multi-Version Runner        ║"
echo "╚══════════════════════════════════════════════════════════════════════╝"
echo ""

mkdir -p "$OUTPUT_BASE/jars"

echo "=== Building Memory Harness ==="
if [[ ! -f "memory-harness/target/databricks-jdbc-memory-harness-1.0.0.jar" ]]; then
    cd memory-harness && mvn clean package -DskipTests -q && cd ..
fi
HARNESS_JAR="$SCRIPT_DIR/memory-harness/target/databricks-jdbc-memory-harness-1.0.0.jar"
echo "Harness JAR: $HARNESS_JAR"
echo ""

download_driver() {
    local version=$1
    local jar_path="$OUTPUT_BASE/jars/databricks-jdbc-${version}.jar"
    mkdir -p "$OUTPUT_BASE/jars"
    
    if [[ -f "$jar_path" ]] && [[ -s "$jar_path" ]]; then
        echo "  Cached: $jar_path" >&2
        DOWNLOADED_JAR="$jar_path"
        return 0
    fi
    
    local url="${MAVEN_REPO}/com/databricks/databricks-jdbc/${version}/databricks-jdbc-${version}.jar"
    echo "  Downloading: $url" >&2
    
    if curl -f -L -o "$jar_path" "$url" 2>/dev/null && [[ -s "$jar_path" ]]; then
        echo "  Downloaded: $(du -h "$jar_path" | cut -f1)" >&2
        DOWNLOADED_JAR="$jar_path"
        return 0
    fi
    
    rm -f "$jar_path"
    DOWNLOADED_JAR=""
    return 1
}

ALL_VERSIONS=$(echo "$VERSIONS" | tr ',' '\n')
VERSION_COUNT=$(echo "$ALL_VERSIONS" | wc -l | tr -d ' ')
echo "Versions to test: $VERSION_COUNT"
echo "$ALL_VERSIONS" | sed 's/^/  - /'
echo ""

SUCCESSFUL_VERSIONS=()
FAILED_VERSIONS=()
SKIPPED_VERSIONS=()

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
echo "Java version:"
"$JAVA" -version 2>&1 | head -3
echo ""

run_version() {
    local version=$1
    local version_output_dir="$OUTPUT_BASE/$version"
    
    echo ""
    echo "════════════════════════════════════════════════════════════════════════"
    echo "Testing version: $version"
    echo "════════════════════════════════════════════════════════════════════════"
    
    if [[ -f "$version_output_dir/summary.json" ]] && [[ "$CONTINUE_MODE" == "true" ]]; then
        echo "Already completed. Skipping..."
        SKIPPED_VERSIONS+=("$version")
        return 0
    fi
    
    mkdir -p "$version_output_dir"
    
    echo "Downloading driver..."
    DOWNLOADED_JAR=""
    if ! download_driver "$version"; then
        echo "ERROR: Failed to download"
        FAILED_VERSIONS+=("$version:download_failed")
        echo '{"status":"failed","reason":"download_failed"}' > "$version_output_dir/status.json"
        return 1
    fi
    
    local driver_jar="$DOWNLOADED_JAR"
    [[ -z "$driver_jar" || ! -f "$driver_jar" ]] && { FAILED_VERSIONS+=("$version:jar_missing"); return 1; }
    
    echo "Using driver: $driver_jar"
    
    local CLASSPATH="$HARNESS_JAR:$driver_jar"
    local JVM_FLAGS=(
        "-Xms${HEAP_SIZE}" "-Xmx${HEAP_SIZE}"
        "-XX:+UseG1GC" "-XX:NativeMemoryTracking=summary"
        "-XX:+UnlockDiagnosticVMOptions"
        "-Xlog:gc*:file=${version_output_dir}/gc.log:time,uptime,level,tags"
        "-XX:+UseCounterDecay" "-XX:+UseStringDeduplication"
        "--add-opens=java.base/java.nio=ALL-UNNAMED"
        "-cp" "$CLASSPATH"
    )
    
    export HARNESS_OUTPUT_DIR="$version_output_dir"
    
    local WORKLOAD_ARGS=""
    [[ "$WORKLOADS" != "all" ]] && WORKLOAD_ARGS=$(echo "$WORKLOADS" | tr ',' ' ')
    
    echo "Running harness..."
    if "$JAVA" "${JVM_FLAGS[@]}" com.databricks.jdbc.memoryharness.MemoryHarnessMain $WORKLOAD_ARGS 2>&1 | tee "$version_output_dir/harness.log"; then
        echo "SUCCESS: $version"
        SUCCESSFUL_VERSIONS+=("$version")
        echo "{\"status\":\"success\",\"version\":\"$version\"}" > "$version_output_dir/status.json"
        [[ -f "$version_output_dir/summary.json" ]] && command -v jq &>/dev/null && {
            TMP=$(mktemp)
            jq --arg v "$version" '.metadata.version_tag=$v' "$version_output_dir/summary.json" > "$TMP" && mv "$TMP" "$version_output_dir/summary.json"
        }
        return 0
    else
        echo "ERROR: Harness failed"
        FAILED_VERSIONS+=("$version:harness_failed")
        echo '{"status":"failed","reason":"harness_failed"}' > "$version_output_dir/status.json"
        return 1
    fi
}

CURRENT=0
for version in $ALL_VERSIONS; do
    CURRENT=$((CURRENT + 1))
    echo ""
    echo "[$CURRENT/$VERSION_COUNT] Processing $version..."
    run_version "$version" || true
done

echo ""
echo "╔══════════════════════════════════════════════════════════════════════╗"
echo "║                          Run Complete                                 ║"
echo "╚══════════════════════════════════════════════════════════════════════╝"
echo ""
echo "Results: $OUTPUT_BASE/"
echo ""
echo "Successful (${#SUCCESSFUL_VERSIONS[@]}):"
for v in "${SUCCESSFUL_VERSIONS[@]}"; do echo "  ✓ $v"; done
echo ""
echo "Failed (${#FAILED_VERSIONS[@]}):"
for v in "${FAILED_VERSIONS[@]}"; do echo "  ✗ $v"; done
echo ""
echo "Skipped (${#SKIPPED_VERSIONS[@]}):"
for v in "${SKIPPED_VERSIONS[@]}"; do echo "  ⊘ $v"; done
echo ""

cat > "$OUTPUT_BASE/run-status.json" << EOF
{
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "total_versions": $VERSION_COUNT,
  "successful_count": ${#SUCCESSFUL_VERSIONS[@]},
  "failed_count": ${#FAILED_VERSIONS[@]},
  "skipped_count": ${#SKIPPED_VERSIONS[@]}
}
EOF

echo "To compare: python3 compare-memory-reports.py $OUTPUT_BASE"
