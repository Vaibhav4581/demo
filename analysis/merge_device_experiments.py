#!/usr/bin/env python3
"""
Decentralized Emergency Mesh Network - Device Experiment Analysis & Report Generator
Processes CSV exports pulled from Android physical devices and aggregates metrics
for Experiments E1 through E6 as defined in PROJECT_REPORT.md.

Usage:
    python analysis/merge_device_experiments.py [path_to_device_logs_dir]
"""

import os
import sys
import glob
import pandas as pd
import numpy as np

def load_all_messages(base_dir):
    """Recursively finds and concatenates all messages.csv files from pulled device logs."""
    pattern = os.path.join(base_dir, "**", "messages.csv")
    files = glob.glob(pattern, recursive=True)
    if not files:
        # Check base_dir itself
        single = os.path.join(base_dir, "messages.csv")
        if os.path.exists(single):
            files = [single]
        else:
            return pd.DataFrame()

    print(f"Found {len(files)} messages.csv file(s).")
    dfs = []
    for f in files:
        try:
            df = pd.read_csv(f)
            df["source_file"] = os.path.basename(os.path.dirname(f))
            dfs.append(df)
        except Exception as e:
            print(f"Warning: could not read {f}: {e}")
    return pd.concat(dfs, ignore_index=True) if dfs else pd.DataFrame()

def load_all_summaries(base_dir):
    """Loads all summary.csv key-value files."""
    pattern = os.path.join(base_dir, "**", "summary.csv")
    files = glob.glob(pattern, recursive=True)
    summaries = []
    for f in files:
        try:
            df = pd.read_csv(f)
            s_dict = dict(zip(df["metric"], df["value"]))
            s_dict["run"] = os.path.basename(os.path.dirname(f))
            summaries.append(s_dict)
        except Exception as e:
            pass
    return pd.DataFrame(summaries) if summaries else pd.DataFrame()

def analyze_e1_hops(df_messages):
    """E1: Delivery and latency versus hop count."""
    print("\n" + "=" * 60)
    print("### E1: Delivery and Latency versus Hop Count")
    print("=" * 60)

    if df_messages.empty or "hop_count" not in df_messages.columns:
        print("No valid hop_count data found.")
        return

    # Filter messages where hop_count is numeric
    df_valid = df_messages.dropna(subset=["hop_count"]).copy()
    df_valid["hop_count"] = df_valid["hop_count"].astype(int)

    rows = []
    for hops, group in df_valid.groupby("hop_count"):
        total = len(group)
        delivered = group["is_delivered"].astype(str).str.lower().eq("true").sum()
        del_rate = (delivered / total) * 100 if total > 0 else 0.0

        latencies = group["latency_ms"].dropna().astype(float)
        median_lat = np.median(latencies) if len(latencies) > 0 else 0.0
        p95_lat = np.percentile(latencies, 95) if len(latencies) > 0 else 0.0

        rows.append({
            "Hops": hops,
            "Messages Sent": total,
            "Delivered": delivered,
            "Delivery Rate": f"{del_rate:.1f}%",
            "Median Latency": f"{median_lat:.1f} ms",
            "95th Percentile": f"{p95_lat:.1f} ms"
        })

    e1_df = pd.DataFrame(rows)
    print(e1_df.to_markdown(index=False) if hasattr(e1_df, "to_markdown") else e1_df.to_string(index=False))

def analyze_e4_dedup(df_summaries):
    """E4: Deduplication effectiveness."""
    print("\n" + "=" * 60)
    print("### E4: Deduplication Effect")
    print("=" * 60)

    if df_summaries.empty or "duplicatesDropped" not in df_summaries.columns:
        print("No summary metrics found for deduplication.")
        return

    cols = ["run", "totalSent", "totalDelivered", "duplicatesDropped", "retransmissions"]
    available_cols = [c for c in cols if c in df_summaries.columns]
    print(df_summaries[available_cols].to_string(index=False))

def main():
    default_dir = os.path.join(os.path.dirname(__file__), "data")
    target_dir = sys.argv[1] if len(sys.argv) > 1 else default_dir

    print(f"Aggregating Android device experiment logs from: {os.path.abspath(target_dir)}")
    if not os.path.exists(target_dir):
        print(f"Directory {target_dir} does not exist yet. Pull logs using adb first.")
        return

    df_messages = load_all_messages(target_dir)
    df_summaries = load_all_summaries(target_dir)

    analyze_e1_hops(df_messages)
    analyze_e4_dedup(df_summaries)

if __name__ == "__main__":
    main()
