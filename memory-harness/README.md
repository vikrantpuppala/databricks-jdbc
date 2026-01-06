# Databricks JDBC Driver - Memory Regression Harness

A repeatable, deterministic benchmarking harness for detecting memory regressions in the Databricks JDBC driver across releases.

## Overview

This harness:
- Builds the JDBC driver
- Runs a fixed set of JDBC workloads
- Captures JVM and OS memory metrics
- Emits structured JSON reports for comparison

## Quick Start

```bash
# Set required environment variables
export HARNESS_JDBC_URL="jdbc:databricks://your-host:443/default;transportMode=http;ssl=1;AuthMech=3;httpPath=/sql/1.0/warehouses/your-warehouse"
export HARNESS_USERNAME="token"
export HARNESS_PASSWORD="your-databricks-token"

# Run the harness (from repo root)
./run-memory-harness.sh
```

## Requirements

- **Java 11+** (JDK, not JRE - jcmd must be available)
- **Maven 3.6+**
- **Linux** (recommended) or macOS
- Network access to Databricks workspace

## Configuration

### Required Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `HARNESS_JDBC_URL` | Full JDBC connection URL | `jdbc:databricks://host:443/default;...` |
| `HARNESS_USERNAME` | Username for authentication | `token` |
| `HARNESS_PASSWORD` | Password or access token | `dapi...` |

### Optional Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `HARNESS_CONN_ITERATIONS` | 100 | Connection lifecycle iterations |
| `HARNESS_CONN_CONCURRENCY` | 4 | Concurrent connections |
| `HARNESS_METADATA_ITERATIONS` | 50 | Metadata workload iterations |
| `HARNESS_RS_SMALL_ITERATIONS` | 100 | Small result set iterations |
| `HARNESS_RS_MEDIUM_ITERATIONS` | 20 | Medium result set iterations |
| `HARNESS_RS_MEDIUM_ROWS` | 5000 | Rows per medium result set |
| `HARNESS_MIXED_DURATION_SECONDS` | 120 | Mixed workload duration |
| `HARNESS_OUTPUT_DIR` | memory-report | Output directory |

### Script Options

```bash
./run-memory-harness.sh [options] [workloads...]

Options:
  --skip-build    Skip building driver and harness
  --heap=SIZE     Set heap size (default: 2g)
  --output=DIR    Set output directory
  --help          Show help message
```

## Workloads

### 1. Connection Lifecycle (`connection-lifecycle`)

Stress tests connection pooling and resource cleanup.

- Opens and closes JDBC connections repeatedly
- Executes `SELECT 1` on each connection
- Configurable iterations and concurrency
- Exposes connection leak issues

### 2. Metadata Heavy (`metadata-heavy`)

Tests DatabaseMetaData operations for retained objects.

- `DatabaseMetaData.getSchemas()`
- `DatabaseMetaData.getTables()`
- `DatabaseMetaData.getColumns()`
- `DatabaseMetaData.getPrimaryKeys()`
- Many iterations to expose memory leaks

### 3. ResultSet Iteration (`resultset-iteration`)

Tests result set memory management.

- **Small ResultSet**: Single-row queries with multiple column types
- **Medium ResultSet**: 1k-10k rows generated via SQL
- Full iteration and column reading
- Tests Arrow buffer management

### 4. Mixed Steady-State (`mixed-steady-state`)

Long-running workload simulating real usage.

- Runs for configurable duration (default 2 minutes)
- Mix of small queries, metadata calls, and medium queries
- Deterministic cycling pattern (5:2:1 ratio)
- Tests steady-state memory behavior

## Metrics Collected

### 1. Heap After GC (`heap-after-gc.json`)

Steady-state heap usage after triggering full GC via `jcmd GC.run`.

| Metric | Unit | Description |
|--------|------|-------------|
| `heap_total_bytes` | bytes | Total heap capacity |
| `heap_used_bytes` | bytes | Heap used after full GC |
| `heap_free_bytes` | bytes | Free heap space |
| `g1_region_size_bytes` | bytes | G1 region size |
| `g1_young_bytes` | bytes | Young generation size |
| `g1_survivor_bytes` | bytes | Survivor space size |
| `mxbean_heap_*` | bytes | MXBean heap metrics (fallback) |

### 2. GC Stats (`gc-stats.json`)

GC activity during workload execution.

| Metric | Unit | Description |
|--------|------|-------------|
| `delta_young_gc_count` | count | Young GC collections during workload |
| `delta_young_gc_time_ms` | ms | Young GC time |
| `delta_old_gc_count` | count | Old/Full GC collections |
| `delta_old_gc_time_ms` | ms | Old GC time |
| `delta_total_gc_count` | count | Total GC collections |
| `delta_total_gc_time_ms` | ms | Total GC pause time |
| `avg_gc_pause_ms` | ms | Average pause time |

### 3. Allocation Rate (`allocation-rate.json`)

Memory allocation behavior.

| Metric | Unit | Description |
|--------|------|-------------|
| `estimated_allocation_bytes` | bytes | Estimated total allocation |
| `allocation_rate_bytes_per_sec` | bytes/s | Allocation rate |
| `allocation_rate_mb_per_sec` | MB/s | Allocation rate (MB/s) |
| `eden_churn_count` | count | Times Eden was collected |
| `young_gc_count_delta` | count | Young GC count |

### 4. Native Memory (`native-memory.json`)

JVM native memory via `jcmd VM.native_memory summary`.

| Metric | Unit | Description |
|--------|------|-------------|
| `total_reserved_bytes` | bytes | Total reserved native memory |
| `total_committed_bytes` | bytes | Total committed native memory |
| `java_heap_*_bytes` | bytes | Java Heap native allocation |
| `class_*_bytes` | bytes | Class/Metaspace allocation |
| `thread_*_bytes` | bytes | Thread stack allocation |
| `code_*_bytes` | bytes | JIT code cache |
| `internal_*_bytes` | bytes | Internal JVM structures |

**Note**: Requires `-XX:NativeMemoryTracking=summary` JVM flag.

### 5. RSS (`rss.json`)

OS-level resident set size.

| Metric | Unit | Description |
|--------|------|-------------|
| `initial_rss_bytes` | bytes | RSS at start |
| `final_rss_bytes` | bytes | RSS at end |
| `peak_rss_bytes` | bytes | Maximum RSS observed |
| `rss_growth_bytes` | bytes | RSS increase |
| `rss_growth_percent` | % | RSS increase percentage |
| `proc_vm*_bytes` | bytes | /proc memory metrics (Linux) |

### 6. Metaspace (`class-metaspace.json`)

Class loading and metaspace usage.

| Metric | Unit | Description |
|--------|------|-------------|
| `loaded_class_count` | count | Currently loaded classes |
| `total_loaded_class_count` | count | Total classes ever loaded |
| `unloaded_class_count` | count | Unloaded classes |
| `delta_loaded_class_count` | count | Classes loaded during workload |
| `metaspace_used_bytes` | bytes | Metaspace used |
| `metaspace_committed_bytes` | bytes | Metaspace committed |
| `code_cache_used_bytes` | bytes | JIT code cache used |

## Output Structure

```
memory-report/
├── run-metadata.json        # Git SHA, timestamp, JVM version, flags
├── heap-after-gc.json       # Heap usage after full GC
├── gc-stats.json            # GC count, pause time, allocation
├── allocation-rate.json     # Allocation rate and Eden churn
├── native-memory.json       # jcmd VM.native_memory summary
├── rss.json                 # Resident Set Size (peak/final)
├── class-metaspace.json     # Metaspace and class loading
├── summary.json             # Aggregate report with all data
└── gc.log                   # Detailed GC log
```

## JVM Configuration

The harness runs workloads in a forked JVM with these flags:

```
-Xms2g -Xmx2g                    # Fixed heap (deterministic)
-XX:+UseG1GC                     # G1 garbage collector
-XX:NativeMemoryTracking=summary # Enable NMT
-XX:+UnlockDiagnosticVMOptions   # Diagnostic options
-Xlog:gc*:file=gc.log           # GC logging
-XX:+UseStringDeduplication      # String dedup (memory savings)
--add-opens=java.base/java.nio=ALL-UNNAMED  # Arrow memory
```

## Comparing Releases

### Approach

1. **Baseline Run**: Run harness on the current stable release
2. **Candidate Run**: Run harness on the candidate release
3. **Compare**: Diff the JSON reports

### Key Metrics to Watch

| Metric | Regression Threshold | Notes |
|--------|---------------------|-------|
| `heap_used_bytes` | > 10% increase | After full GC |
| `peak_rss_bytes` | > 15% increase | OS-level memory |
| `metaspace_used_bytes` | > 20% increase | Class loading |
| `delta_total_gc_time_ms` | > 20% increase | GC overhead |
| `loaded_class_count` | > 10% increase | Classloader leaks |

### Manual Comparison Example

```bash
# Run on v1.0.0
git checkout v1.0.0
./run-memory-harness.sh --output=reports/v1.0.0

# Run on v1.1.0
git checkout v1.1.0
./run-memory-harness.sh --output=reports/v1.1.0

# Compare key metrics
jq '.key_metrics' reports/v1.0.0/summary.json
jq '.key_metrics' reports/v1.1.0/summary.json
```

## Troubleshooting

### "jcmd not available"

Ensure you're using a JDK (not JRE) and `jcmd` is in your PATH:
```bash
which jcmd
# or
$JAVA_HOME/bin/jcmd -h
```

### "NMT not enabled"

The native memory collector requires the JVM flag. The harness script adds this automatically, but if running manually:
```bash
java -XX:NativeMemoryTracking=summary ...
```

### Connection Failures

1. Verify environment variables are set correctly
2. Check network access to Databricks workspace
3. Ensure the warehouse is running
4. Check access token validity

### Out of Memory

Increase heap size:
```bash
./run-memory-harness.sh --heap=4g
```

## Development

### Building Manually

```bash
# Build driver
mvn clean package -DskipTests

# Build harness
cd memory-harness
mvn clean package -DskipTests
```

### Running Specific Workloads

```bash
# Single workload
./run-memory-harness.sh connection-lifecycle

# Multiple workloads
./run-memory-harness.sh connection-lifecycle metadata-heavy
```

### Adding New Workloads

1. Create a class implementing `Workload` interface
2. Add to `availableWorkloads` map in `MemoryHarnessMain`
3. Document in this README

## License

Same as parent Databricks JDBC driver project.

