#!/usr/bin/env python3
"""
Databricks JDBC Driver - Memory Report Comparison Tool

Compares memory harness reports across multiple driver versions
to identify regressions and trends.

Usage:
    python3 compare-memory-reports.py <reports-directory> [options]

Options:
    --output=FORMAT     Output format: text, json, html, csv (default: text)
    --baseline=VERSION  Specify baseline version for comparison
    --threshold=PCT     Regression threshold percentage (default: 10)
    --metric=NAME       Focus on specific metric(s), comma-separated
    --sort=FIELD        Sort by: version, heap, gc, rss (default: version)
    --export=FILE       Export results to file

Example:
    python3 compare-memory-reports.py memory-reports/
    python3 compare-memory-reports.py memory-reports/ --baseline=v1.0.0-oss --output=html
"""

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
import re


@dataclass
class VersionMetrics:
    """Metrics collected for a single version."""
    version: str
    timestamp: str = ""
    git_sha: str = ""
    
    # Heap metrics (after GC)
    heap_used_bytes: int = 0
    heap_total_bytes: int = 0
    
    # GC metrics
    gc_count: int = 0
    gc_time_ms: int = 0
    young_gc_count: int = 0
    old_gc_count: int = 0
    avg_gc_pause_ms: float = 0.0
    
    # RSS metrics
    rss_bytes: int = 0
    peak_rss_bytes: int = 0
    rss_growth_bytes: int = 0
    
    # Metaspace metrics
    metaspace_used_bytes: int = 0
    loaded_class_count: int = 0
    
    # Allocation metrics
    estimated_allocation_bytes: int = 0
    allocation_rate_mb_per_sec: float = 0.0
    
    # Native memory
    native_total_committed_bytes: int = 0
    native_total_reserved_bytes: int = 0
    
    # Workload stats
    total_duration_ms: int = 0
    total_operations: int = 0
    operations_per_second: float = 0.0
    failed_operations: int = 0
    
    # Status
    status: str = "success"
    error_reason: str = ""


@dataclass
class ComparisonResult:
    """Result of comparing two versions."""
    metric_name: str
    baseline_value: float
    current_value: float
    delta: float
    delta_percent: float
    is_regression: bool
    severity: str  # low, medium, high, critical


@dataclass
class VersionComparison:
    """Full comparison between baseline and a version."""
    baseline_version: str
    current_version: str
    comparisons: List[ComparisonResult] = field(default_factory=list)
    has_regression: bool = False
    regression_count: int = 0
    improvement_count: int = 0


def parse_version(version: str) -> Tuple:
    """Parse version string for sorting."""
    # Remove 'v' prefix and split
    v = version.lstrip('v')
    
    # Handle suffixes like -oss, -beta
    parts = v.split('-')
    main_version = parts[0]
    suffix = parts[1] if len(parts) > 1 else 'zzz'  # zzz sorts after everything
    
    # Parse version numbers
    try:
        nums = [int(x) for x in main_version.split('.')]
        while len(nums) < 4:
            nums.append(0)
        return tuple(nums) + (suffix,)
    except ValueError:
        return (0, 0, 0, 0, v)


def load_version_metrics(version_dir: Path) -> Optional[VersionMetrics]:
    """Load metrics from a version's report directory."""
    summary_path = version_dir / "summary.json"
    status_path = version_dir / "status.json"
    
    version = version_dir.name
    metrics = VersionMetrics(version=version)
    
    # Check status
    if status_path.exists():
        try:
            with open(status_path) as f:
                status = json.load(f)
                if status.get("status") != "success":
                    metrics.status = "failed"
                    metrics.error_reason = status.get("reason", "unknown")
                    return metrics
        except (json.JSONDecodeError, IOError):
            pass
    
    # Load summary
    if not summary_path.exists():
        metrics.status = "missing"
        metrics.error_reason = "summary.json not found"
        return metrics
    
    try:
        with open(summary_path) as f:
            summary = json.load(f)
    except (json.JSONDecodeError, IOError) as e:
        metrics.status = "error"
        metrics.error_reason = str(e)
        return metrics
    
    # Extract metadata
    metadata = summary.get("metadata", {})
    metrics.timestamp = metadata.get("timestamp", "")
    metrics.git_sha = metadata.get("git_sha", "")
    
    # Extract key metrics
    key_metrics = summary.get("key_metrics", {})
    metrics.heap_used_bytes = key_metrics.get("heap_used_bytes", 0)
    metrics.gc_count = key_metrics.get("gc_count", 0)
    metrics.gc_time_ms = key_metrics.get("gc_time_ms", 0)
    metrics.rss_bytes = key_metrics.get("rss_bytes", 0)
    metrics.peak_rss_bytes = key_metrics.get("peak_rss_bytes", 0)
    metrics.metaspace_used_bytes = key_metrics.get("metaspace_used_bytes", 0)
    metrics.loaded_class_count = key_metrics.get("loaded_class_count", 0)
    metrics.estimated_allocation_bytes = key_metrics.get("estimated_allocation_bytes", 0)
    metrics.allocation_rate_mb_per_sec = key_metrics.get("allocation_rate_mb_per_sec", 0.0)
    
    # Load additional files for more detail
    heap_path = version_dir / "heap-after-gc.json"
    if heap_path.exists():
        try:
            with open(heap_path) as f:
                heap = json.load(f)
                metrics.heap_total_bytes = heap.get("heap_total_bytes", 0)
        except (json.JSONDecodeError, IOError):
            pass
    
    gc_path = version_dir / "gc-stats.json"
    if gc_path.exists():
        try:
            with open(gc_path) as f:
                gc = json.load(f)
                metrics.young_gc_count = gc.get("delta_young_gc_count", gc.get("young_gc_count", 0))
                metrics.old_gc_count = gc.get("delta_old_gc_count", gc.get("old_gc_count", 0))
                metrics.avg_gc_pause_ms = gc.get("avg_gc_pause_ms", 0.0)
        except (json.JSONDecodeError, IOError):
            pass
    
    native_path = version_dir / "native-memory.json"
    if native_path.exists():
        try:
            with open(native_path) as f:
                native = json.load(f)
                metrics.native_total_committed_bytes = native.get("total_committed_bytes", 0)
                metrics.native_total_reserved_bytes = native.get("total_reserved_bytes", 0)
        except (json.JSONDecodeError, IOError):
            pass
    
    rss_path = version_dir / "rss.json"
    if rss_path.exists():
        try:
            with open(rss_path) as f:
                rss = json.load(f)
                metrics.rss_growth_bytes = rss.get("rss_growth_bytes", 0)
        except (json.JSONDecodeError, IOError):
            pass
    
    # Extract workload stats
    workloads = summary.get("workloads", [])
    for wl in workloads:
        metrics.total_duration_ms += wl.get("duration_ms", 0)
        metrics.total_operations += wl.get("total_operations", 0)
        metrics.failed_operations += wl.get("failed_operations", 0)
    
    if metrics.total_duration_ms > 0:
        metrics.operations_per_second = (metrics.total_operations * 1000) / metrics.total_duration_ms
    
    return metrics


def format_bytes(b: int) -> str:
    """Format bytes in human-readable form."""
    if b == 0:
        return "0 B"
    for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
        if abs(b) < 1024:
            return f"{b:.1f} {unit}"
        b /= 1024
    return f"{b:.1f} PB"


def format_ms(ms: int) -> str:
    """Format milliseconds in human-readable form."""
    if ms < 1000:
        return f"{ms} ms"
    elif ms < 60000:
        return f"{ms/1000:.1f} s"
    else:
        return f"{ms/60000:.1f} min"


def compare_metrics(
    baseline: VersionMetrics,
    current: VersionMetrics,
    threshold_percent: float = 10.0
) -> VersionComparison:
    """Compare metrics between baseline and current version."""
    
    comparison = VersionComparison(
        baseline_version=baseline.version,
        current_version=current.version
    )
    
    # Define metrics to compare with their regression direction
    # (metric_name, baseline_val, current_val, higher_is_worse)
    metrics_to_compare = [
        ("heap_used_bytes", baseline.heap_used_bytes, current.heap_used_bytes, True),
        ("gc_count", baseline.gc_count, current.gc_count, True),
        ("gc_time_ms", baseline.gc_time_ms, current.gc_time_ms, True),
        ("avg_gc_pause_ms", baseline.avg_gc_pause_ms, current.avg_gc_pause_ms, True),
        ("peak_rss_bytes", baseline.peak_rss_bytes, current.peak_rss_bytes, True),
        ("metaspace_used_bytes", baseline.metaspace_used_bytes, current.metaspace_used_bytes, True),
        ("loaded_class_count", baseline.loaded_class_count, current.loaded_class_count, True),
        ("allocation_rate_mb_per_sec", baseline.allocation_rate_mb_per_sec, current.allocation_rate_mb_per_sec, True),
        ("native_total_committed_bytes", baseline.native_total_committed_bytes, current.native_total_committed_bytes, True),
        ("operations_per_second", baseline.operations_per_second, current.operations_per_second, False),
        ("failed_operations", baseline.failed_operations, current.failed_operations, True),
    ]
    
    for metric_name, base_val, curr_val, higher_is_worse in metrics_to_compare:
        if base_val == 0 and curr_val == 0:
            continue
        
        delta = curr_val - base_val
        if base_val != 0:
            delta_percent = (delta / base_val) * 100
        else:
            delta_percent = 100.0 if curr_val > 0 else 0.0
        
        # Determine if this is a regression
        if higher_is_worse:
            is_regression = delta_percent > threshold_percent
        else:
            is_regression = delta_percent < -threshold_percent
        
        # Determine severity
        abs_delta = abs(delta_percent)
        if abs_delta < threshold_percent:
            severity = "ok"
        elif abs_delta < threshold_percent * 2:
            severity = "low"
        elif abs_delta < threshold_percent * 5:
            severity = "medium"
        elif abs_delta < threshold_percent * 10:
            severity = "high"
        else:
            severity = "critical"
        
        result = ComparisonResult(
            metric_name=metric_name,
            baseline_value=base_val,
            current_value=curr_val,
            delta=delta,
            delta_percent=delta_percent,
            is_regression=is_regression,
            severity=severity if is_regression else "ok"
        )
        comparison.comparisons.append(result)
        
        if is_regression:
            comparison.has_regression = True
            comparison.regression_count += 1
        elif delta_percent < -threshold_percent and higher_is_worse:
            comparison.improvement_count += 1
    
    return comparison


def generate_text_report(
    versions: List[VersionMetrics],
    baseline: Optional[VersionMetrics] = None,
    threshold: float = 10.0
) -> str:
    """Generate a text-based comparison report."""
    lines = []
    
    lines.append("=" * 90)
    lines.append("  DATABRICKS JDBC DRIVER - MEMORY PERFORMANCE COMPARISON REPORT")
    lines.append("=" * 90)
    lines.append(f"  Generated: {datetime.now().isoformat()}")
    lines.append(f"  Versions analyzed: {len(versions)}")
    lines.append(f"  Regression threshold: {threshold}%")
    lines.append("")
    
    # Summary table
    lines.append("-" * 90)
    lines.append("  VERSION SUMMARY")
    lines.append("-" * 90)
    lines.append(f"{'Version':<18} {'Status':<10} {'Heap Used':<12} {'Peak RSS':<12} {'GC Time':<10} {'GC Count':<10} {'Metaspace':<12}")
    lines.append("-" * 90)
    
    for v in versions:
        if v.status != "success":
            lines.append(f"{v.version:<18} {'FAILED':<10} {v.error_reason}")
            continue
        
        lines.append(
            f"{v.version:<18} "
            f"{'OK':<10} "
            f"{format_bytes(v.heap_used_bytes):<12} "
            f"{format_bytes(v.peak_rss_bytes):<12} "
            f"{format_ms(v.gc_time_ms):<10} "
            f"{v.gc_count:<10} "
            f"{format_bytes(v.metaspace_used_bytes):<12}"
        )
    
    lines.append("-" * 90)
    lines.append("")
    
    # Find baseline
    if baseline is None and versions:
        # Use first successful version as baseline
        for v in versions:
            if v.status == "success":
                baseline = v
                break
    
    if baseline is None:
        lines.append("No successful baseline version found for comparison.")
        return "\n".join(lines)
    
    lines.append(f"  Baseline: {baseline.version}")
    lines.append("")
    
    # Detailed comparisons
    lines.append("-" * 90)
    lines.append("  REGRESSION ANALYSIS (vs baseline)")
    lines.append("-" * 90)
    
    regressions_found = False
    
    for v in versions:
        if v.status != "success" or v.version == baseline.version:
            continue
        
        comparison = compare_metrics(baseline, v, threshold)
        
        if comparison.has_regression:
            regressions_found = True
            lines.append(f"\n  ⚠️  {v.version} - {comparison.regression_count} regression(s) detected")
            lines.append("  " + "-" * 60)
            
            for c in comparison.comparisons:
                if c.is_regression:
                    indicator = "🔴" if c.severity in ["high", "critical"] else "🟡"
                    lines.append(
                        f"    {indicator} {c.metric_name}: "
                        f"{c.baseline_value:.2f} → {c.current_value:.2f} "
                        f"({c.delta_percent:+.1f}%) [{c.severity}]"
                    )
        
        if comparison.improvement_count > 0:
            lines.append(f"\n  ✅ {v.version} - {comparison.improvement_count} improvement(s)")
            for c in comparison.comparisons:
                if c.delta_percent < -threshold and c.severity == "ok":
                    lines.append(
                        f"    🟢 {c.metric_name}: "
                        f"{c.baseline_value:.2f} → {c.current_value:.2f} "
                        f"({c.delta_percent:+.1f}%)"
                    )
    
    if not regressions_found:
        lines.append("\n  ✅ No regressions detected across all versions!")
    
    lines.append("")
    lines.append("=" * 90)
    
    # Trends section
    lines.append("")
    lines.append("-" * 90)
    lines.append("  METRIC TRENDS (across versions)")
    lines.append("-" * 90)
    
    successful = [v for v in versions if v.status == "success"]
    if len(successful) >= 2:
        # Heap trend
        heap_values = [(v.version, v.heap_used_bytes) for v in successful]
        lines.append("\n  Heap Used (after GC):")
        for version, value in heap_values:
            bar_len = min(int(value / (1024 * 1024)), 50)  # Scale: 1 char = 1MB, max 50
            bar = "█" * bar_len
            lines.append(f"    {version:<18} {bar} {format_bytes(value)}")
        
        # RSS trend
        lines.append("\n  Peak RSS:")
        rss_values = [(v.version, v.peak_rss_bytes) for v in successful]
        max_rss = max(r[1] for r in rss_values) if rss_values else 1
        for version, value in rss_values:
            bar_len = int((value / max_rss) * 30)
            bar = "█" * bar_len
            lines.append(f"    {version:<18} {bar} {format_bytes(value)}")
        
        # GC time trend
        lines.append("\n  GC Time:")
        gc_values = [(v.version, v.gc_time_ms) for v in successful]
        max_gc = max(g[1] for g in gc_values) if gc_values else 1
        for version, value in gc_values:
            bar_len = int((value / max_gc) * 30) if max_gc > 0 else 0
            bar = "█" * bar_len
            lines.append(f"    {version:<18} {bar} {format_ms(value)}")
    
    lines.append("")
    lines.append("=" * 90)
    
    return "\n".join(lines)


def generate_json_report(
    versions: List[VersionMetrics],
    baseline: Optional[VersionMetrics] = None,
    threshold: float = 10.0
) -> str:
    """Generate a JSON comparison report."""
    
    report = {
        "generated_at": datetime.now().isoformat(),
        "version_count": len(versions),
        "threshold_percent": threshold,
        "versions": [],
        "comparisons": [],
        "summary": {
            "regressions_found": 0,
            "improvements_found": 0,
            "versions_with_issues": []
        }
    }
    
    # Add version data
    for v in versions:
        version_data = {
            "version": v.version,
            "status": v.status,
            "timestamp": v.timestamp,
            "git_sha": v.git_sha,
            "metrics": {}
        }
        
        if v.status == "success":
            version_data["metrics"] = {
                "heap_used_bytes": v.heap_used_bytes,
                "peak_rss_bytes": v.peak_rss_bytes,
                "gc_count": v.gc_count,
                "gc_time_ms": v.gc_time_ms,
                "avg_gc_pause_ms": v.avg_gc_pause_ms,
                "metaspace_used_bytes": v.metaspace_used_bytes,
                "loaded_class_count": v.loaded_class_count,
                "allocation_rate_mb_per_sec": v.allocation_rate_mb_per_sec,
                "native_total_committed_bytes": v.native_total_committed_bytes,
                "operations_per_second": v.operations_per_second,
                "failed_operations": v.failed_operations
            }
        else:
            version_data["error_reason"] = v.error_reason
        
        report["versions"].append(version_data)
    
    # Find baseline
    if baseline is None:
        for v in versions:
            if v.status == "success":
                baseline = v
                break
    
    if baseline:
        report["baseline_version"] = baseline.version
        
        # Generate comparisons
        for v in versions:
            if v.status != "success" or v.version == baseline.version:
                continue
            
            comparison = compare_metrics(baseline, v, threshold)
            
            comparison_data = {
                "version": v.version,
                "vs_baseline": baseline.version,
                "has_regression": comparison.has_regression,
                "regression_count": comparison.regression_count,
                "improvement_count": comparison.improvement_count,
                "metrics": []
            }
            
            for c in comparison.comparisons:
                comparison_data["metrics"].append({
                    "name": c.metric_name,
                    "baseline": c.baseline_value,
                    "current": c.current_value,
                    "delta": c.delta,
                    "delta_percent": c.delta_percent,
                    "is_regression": c.is_regression,
                    "severity": c.severity
                })
            
            report["comparisons"].append(comparison_data)
            
            if comparison.has_regression:
                report["summary"]["regressions_found"] += comparison.regression_count
                report["summary"]["versions_with_issues"].append(v.version)
            
            report["summary"]["improvements_found"] += comparison.improvement_count
    
    return json.dumps(report, indent=2)


def generate_csv_report(
    versions: List[VersionMetrics],
    baseline: Optional[VersionMetrics] = None,
    threshold: float = 10.0
) -> str:
    """Generate a CSV comparison report."""
    
    lines = []
    
    # Header
    headers = [
        "version", "status", "timestamp", "git_sha",
        "heap_used_bytes", "peak_rss_bytes", "gc_count", "gc_time_ms",
        "avg_gc_pause_ms", "metaspace_used_bytes", "loaded_class_count",
        "allocation_rate_mb_per_sec", "native_total_committed_bytes",
        "operations_per_second", "failed_operations"
    ]
    lines.append(",".join(headers))
    
    # Data rows
    for v in versions:
        if v.status == "success":
            row = [
                v.version, v.status, v.timestamp, v.git_sha,
                str(v.heap_used_bytes), str(v.peak_rss_bytes),
                str(v.gc_count), str(v.gc_time_ms),
                f"{v.avg_gc_pause_ms:.2f}", str(v.metaspace_used_bytes),
                str(v.loaded_class_count), f"{v.allocation_rate_mb_per_sec:.2f}",
                str(v.native_total_committed_bytes),
                f"{v.operations_per_second:.2f}", str(v.failed_operations)
            ]
        else:
            row = [v.version, v.status, "", "", "", "", "", "", "", "", "", "", "", "", ""]
        
        lines.append(",".join(row))
    
    return "\n".join(lines)


def generate_html_report(
    versions: List[VersionMetrics],
    baseline: Optional[VersionMetrics] = None,
    threshold: float = 10.0
) -> str:
    """Generate an HTML comparison report with charts."""
    
    # Find baseline if not specified
    if baseline is None:
        for v in versions:
            if v.status == "success":
                baseline = v
                break
    
    successful = [v for v in versions if v.status == "success"]
    
    html = f'''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>JDBC Memory Performance Report</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        :root {{
            --bg-primary: #0d1117;
            --bg-secondary: #161b22;
            --bg-tertiary: #21262d;
            --text-primary: #c9d1d9;
            --text-secondary: #8b949e;
            --accent: #58a6ff;
            --success: #3fb950;
            --warning: #d29922;
            --danger: #f85149;
            --border: #30363d;
        }}
        
        * {{
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }}
        
        body {{
            font-family: 'SF Mono', 'Consolas', 'Monaco', monospace;
            background: var(--bg-primary);
            color: var(--text-primary);
            line-height: 1.6;
            padding: 2rem;
        }}
        
        .container {{
            max-width: 1400px;
            margin: 0 auto;
        }}
        
        header {{
            text-align: center;
            margin-bottom: 3rem;
            padding: 2rem;
            background: linear-gradient(135deg, var(--bg-secondary), var(--bg-tertiary));
            border-radius: 12px;
            border: 1px solid var(--border);
        }}
        
        h1 {{
            font-size: 2rem;
            font-weight: 600;
            color: var(--accent);
            margin-bottom: 0.5rem;
        }}
        
        .subtitle {{
            color: var(--text-secondary);
            font-size: 0.9rem;
        }}
        
        .stats-row {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 1.5rem;
            margin-bottom: 2rem;
        }}
        
        .stat-card {{
            background: var(--bg-secondary);
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 1.5rem;
            text-align: center;
        }}
        
        .stat-value {{
            font-size: 2rem;
            font-weight: 700;
            color: var(--accent);
        }}
        
        .stat-value.danger {{
            color: var(--danger);
        }}
        
        .stat-value.success {{
            color: var(--success);
        }}
        
        .stat-label {{
            color: var(--text-secondary);
            font-size: 0.85rem;
            margin-top: 0.5rem;
        }}
        
        .section {{
            background: var(--bg-secondary);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 1.5rem;
            margin-bottom: 2rem;
        }}
        
        .section-title {{
            font-size: 1.2rem;
            color: var(--accent);
            margin-bottom: 1rem;
            padding-bottom: 0.5rem;
            border-bottom: 1px solid var(--border);
        }}
        
        table {{
            width: 100%;
            border-collapse: collapse;
            font-size: 0.85rem;
        }}
        
        th, td {{
            padding: 0.75rem;
            text-align: left;
            border-bottom: 1px solid var(--border);
        }}
        
        th {{
            background: var(--bg-tertiary);
            color: var(--accent);
            font-weight: 600;
        }}
        
        tr:hover {{
            background: var(--bg-tertiary);
        }}
        
        .status-ok {{ color: var(--success); }}
        .status-warning {{ color: var(--warning); }}
        .status-danger {{ color: var(--danger); }}
        
        .delta {{
            font-size: 0.8rem;
            padding: 0.2rem 0.5rem;
            border-radius: 4px;
        }}
        
        .delta.positive {{
            background: rgba(248, 81, 73, 0.2);
            color: var(--danger);
        }}
        
        .delta.negative {{
            background: rgba(63, 185, 80, 0.2);
            color: var(--success);
        }}
        
        .chart-container {{
            position: relative;
            height: 300px;
            margin: 1rem 0;
        }}
        
        .chart-grid {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
            gap: 1.5rem;
        }}
        
        .chart-card {{
            background: var(--bg-tertiary);
            border-radius: 8px;
            padding: 1rem;
        }}
        
        .chart-title {{
            color: var(--text-secondary);
            font-size: 0.9rem;
            margin-bottom: 0.5rem;
        }}
        
        .baseline-badge {{
            display: inline-block;
            background: var(--accent);
            color: var(--bg-primary);
            padding: 0.2rem 0.5rem;
            border-radius: 4px;
            font-size: 0.75rem;
            margin-left: 0.5rem;
        }}
        
        footer {{
            text-align: center;
            color: var(--text-secondary);
            font-size: 0.8rem;
            margin-top: 2rem;
            padding-top: 1rem;
            border-top: 1px solid var(--border);
        }}
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>📊 JDBC Memory Performance Report</h1>
            <p class="subtitle">Generated: {datetime.now().strftime("%Y-%m-%d %H:%M:%S")} | Baseline: {baseline.version if baseline else 'N/A'} | Threshold: {threshold}%</p>
        </header>
'''

    # Summary stats
    total_versions = len(versions)
    successful_count = len(successful)
    failed_count = total_versions - successful_count
    
    # Count regressions
    regression_count = 0
    if baseline:
        for v in successful:
            if v.version != baseline.version:
                comparison = compare_metrics(baseline, v, threshold)
                regression_count += comparison.regression_count
    
    html += f'''
        <div class="stats-row">
            <div class="stat-card">
                <div class="stat-value">{total_versions}</div>
                <div class="stat-label">Total Versions</div>
            </div>
            <div class="stat-card">
                <div class="stat-value success">{successful_count}</div>
                <div class="stat-label">Successful</div>
            </div>
            <div class="stat-card">
                <div class="stat-value danger">{failed_count}</div>
                <div class="stat-label">Failed</div>
            </div>
            <div class="stat-card">
                <div class="stat-value {"danger" if regression_count > 0 else "success"}">{regression_count}</div>
                <div class="stat-label">Regressions Detected</div>
            </div>
        </div>
'''

    # Version summary table
    html += '''
        <div class="section">
            <h2 class="section-title">Version Summary</h2>
            <table>
                <thead>
                    <tr>
                        <th>Version</th>
                        <th>Status</th>
                        <th>Heap Used</th>
                        <th>Peak RSS</th>
                        <th>GC Time</th>
                        <th>GC Count</th>
                        <th>Metaspace</th>
                        <th>Allocation Rate</th>
                    </tr>
                </thead>
                <tbody>
'''
    
    for v in versions:
        if v.status == "success":
            # Calculate deltas if we have baseline
            heap_delta = ""
            rss_delta = ""
            gc_delta = ""
            if baseline and v.version != baseline.version:
                if baseline.heap_used_bytes > 0:
                    pct = ((v.heap_used_bytes - baseline.heap_used_bytes) / baseline.heap_used_bytes) * 100
                    cls = "positive" if pct > 0 else "negative"
                    heap_delta = f'<span class="delta {cls}">{pct:+.1f}%</span>'
                if baseline.peak_rss_bytes > 0:
                    pct = ((v.peak_rss_bytes - baseline.peak_rss_bytes) / baseline.peak_rss_bytes) * 100
                    cls = "positive" if pct > 0 else "negative"
                    rss_delta = f'<span class="delta {cls}">{pct:+.1f}%</span>'
                if baseline.gc_time_ms > 0:
                    pct = ((v.gc_time_ms - baseline.gc_time_ms) / baseline.gc_time_ms) * 100
                    cls = "positive" if pct > 0 else "negative"
                    gc_delta = f'<span class="delta {cls}">{pct:+.1f}%</span>'
            
            baseline_badge = '<span class="baseline-badge">BASELINE</span>' if baseline and v.version == baseline.version else ""
            
            html += f'''
                    <tr>
                        <td>{v.version}{baseline_badge}</td>
                        <td class="status-ok">✓ OK</td>
                        <td>{format_bytes(v.heap_used_bytes)} {heap_delta}</td>
                        <td>{format_bytes(v.peak_rss_bytes)} {rss_delta}</td>
                        <td>{format_ms(v.gc_time_ms)} {gc_delta}</td>
                        <td>{v.gc_count}</td>
                        <td>{format_bytes(v.metaspace_used_bytes)}</td>
                        <td>{v.allocation_rate_mb_per_sec:.1f} MB/s</td>
                    </tr>
'''
        else:
            html += f'''
                    <tr>
                        <td>{v.version}</td>
                        <td class="status-danger">✗ {v.status}</td>
                        <td colspan="6">{v.error_reason}</td>
                    </tr>
'''
    
    html += '''
                </tbody>
            </table>
        </div>
'''

    # Charts
    if successful:
        version_labels = [v.version for v in successful]
        heap_data = [v.heap_used_bytes / (1024 * 1024) for v in successful]  # MB
        rss_data = [v.peak_rss_bytes / (1024 * 1024) for v in successful]  # MB
        gc_time_data = [v.gc_time_ms for v in successful]
        metaspace_data = [v.metaspace_used_bytes / (1024 * 1024) for v in successful]  # MB
        
        html += f'''
        <div class="section">
            <h2 class="section-title">Memory Trends</h2>
            <div class="chart-grid">
                <div class="chart-card">
                    <div class="chart-title">Heap Used After GC (MB)</div>
                    <div class="chart-container">
                        <canvas id="heapChart"></canvas>
                    </div>
                </div>
                <div class="chart-card">
                    <div class="chart-title">Peak RSS (MB)</div>
                    <div class="chart-container">
                        <canvas id="rssChart"></canvas>
                    </div>
                </div>
                <div class="chart-card">
                    <div class="chart-title">GC Time (ms)</div>
                    <div class="chart-container">
                        <canvas id="gcChart"></canvas>
                    </div>
                </div>
                <div class="chart-card">
                    <div class="chart-title">Metaspace (MB)</div>
                    <div class="chart-container">
                        <canvas id="metaspaceChart"></canvas>
                    </div>
                </div>
            </div>
        </div>

        <script>
            const chartConfig = {{
                type: 'line',
                options: {{
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {{
                        legend: {{ display: false }},
                    }},
                    scales: {{
                        x: {{
                            ticks: {{ color: '#8b949e', maxRotation: 45, minRotation: 45 }},
                            grid: {{ color: '#30363d' }}
                        }},
                        y: {{
                            ticks: {{ color: '#8b949e' }},
                            grid: {{ color: '#30363d' }}
                        }}
                    }}
                }}
            }};

            const labels = {json.dumps(version_labels)};

            new Chart(document.getElementById('heapChart'), {{
                ...chartConfig,
                data: {{
                    labels: labels,
                    datasets: [{{
                        data: {json.dumps(heap_data)},
                        borderColor: '#58a6ff',
                        backgroundColor: 'rgba(88, 166, 255, 0.1)',
                        fill: true,
                        tension: 0.3
                    }}]
                }}
            }});

            new Chart(document.getElementById('rssChart'), {{
                ...chartConfig,
                data: {{
                    labels: labels,
                    datasets: [{{
                        data: {json.dumps(rss_data)},
                        borderColor: '#f78166',
                        backgroundColor: 'rgba(247, 129, 102, 0.1)',
                        fill: true,
                        tension: 0.3
                    }}]
                }}
            }});

            new Chart(document.getElementById('gcChart'), {{
                ...chartConfig,
                data: {{
                    labels: labels,
                    datasets: [{{
                        data: {json.dumps(gc_time_data)},
                        borderColor: '#3fb950',
                        backgroundColor: 'rgba(63, 185, 80, 0.1)',
                        fill: true,
                        tension: 0.3
                    }}]
                }}
            }});

            new Chart(document.getElementById('metaspaceChart'), {{
                ...chartConfig,
                data: {{
                    labels: labels,
                    datasets: [{{
                        data: {json.dumps(metaspace_data)},
                        borderColor: '#d29922',
                        backgroundColor: 'rgba(210, 153, 34, 0.1)',
                        fill: true,
                        tension: 0.3
                    }}]
                }}
            }});
        </script>
'''

    html += '''
        <footer>
            <p>Databricks JDBC Driver Memory Harness | Performance Comparison Report</p>
        </footer>
    </div>
</body>
</html>
'''
    
    return html


def main():
    parser = argparse.ArgumentParser(
        description="Compare memory harness reports across JDBC driver versions",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    
    parser.add_argument(
        "reports_dir",
        help="Directory containing version report subdirectories"
    )
    
    parser.add_argument(
        "--output", "-o",
        choices=["text", "json", "html", "csv"],
        default="text",
        help="Output format (default: text)"
    )
    
    parser.add_argument(
        "--baseline", "-b",
        help="Specify baseline version for comparison"
    )
    
    parser.add_argument(
        "--threshold", "-t",
        type=float,
        default=10.0,
        help="Regression threshold percentage (default: 10)"
    )
    
    parser.add_argument(
        "--export", "-e",
        help="Export results to file"
    )
    
    parser.add_argument(
        "--sort", "-s",
        choices=["version", "heap", "gc", "rss"],
        default="version",
        help="Sort order (default: version)"
    )
    
    args = parser.parse_args()
    
    reports_path = Path(args.reports_dir)
    
    if not reports_path.exists():
        print(f"Error: Directory not found: {reports_path}")
        sys.exit(1)
    
    # Find all version directories
    version_dirs = []
    for item in reports_path.iterdir():
        if item.is_dir() and (item / "summary.json").exists() or (item / "status.json").exists():
            version_dirs.append(item)
    
    if not version_dirs:
        print(f"Error: No version reports found in {reports_path}")
        print("Each version should have its own subdirectory with summary.json")
        sys.exit(1)
    
    # Load metrics for each version
    versions = []
    for vdir in version_dirs:
        metrics = load_version_metrics(vdir)
        if metrics:
            versions.append(metrics)
    
    # Sort versions
    if args.sort == "version":
        versions.sort(key=lambda v: parse_version(v.version))
    elif args.sort == "heap":
        versions.sort(key=lambda v: v.heap_used_bytes)
    elif args.sort == "gc":
        versions.sort(key=lambda v: v.gc_time_ms)
    elif args.sort == "rss":
        versions.sort(key=lambda v: v.peak_rss_bytes)
    
    # Find baseline
    baseline = None
    if args.baseline:
        for v in versions:
            if v.version == args.baseline:
                baseline = v
                break
        if baseline is None:
            print(f"Warning: Baseline version '{args.baseline}' not found")
    
    # Generate report
    if args.output == "text":
        report = generate_text_report(versions, baseline, args.threshold)
    elif args.output == "json":
        report = generate_json_report(versions, baseline, args.threshold)
    elif args.output == "csv":
        report = generate_csv_report(versions, baseline, args.threshold)
    elif args.output == "html":
        report = generate_html_report(versions, baseline, args.threshold)
    
    # Output
    if args.export:
        with open(args.export, "w") as f:
            f.write(report)
        print(f"Report exported to: {args.export}")
    else:
        print(report)


if __name__ == "__main__":
    main()
