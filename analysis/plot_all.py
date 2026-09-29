#!/usr/bin/env python3
"""
Decentralized Emergency Mesh Network - Simulation Analysis and Plotting
Reads exported CSV files from mesh-sim and produces charts:
1. Delivery rate vs hop count
2. End-to-end latency vs hop count
3. Routing convergence and recovery timeline
"""

import os
import sys
import pandas as pd
import matplotlib.pyplot as plt

def find_data_dir():
    candidates = [
        os.path.join(os.getcwd(), "analysis", "data"),
        os.path.join(os.getcwd(), "mesh-sim", "analysis", "data"),
        os.path.join(os.path.dirname(__file__), "data"),
        os.path.join(os.path.dirname(__file__), "..", "mesh-sim", "analysis", "data")
    ]
    for c in candidates:
        if os.path.exists(os.path.join(c, "messages.csv")):
            return os.path.abspath(c)
    return os.path.abspath(candidates[0])

def main():
    data_dir = sys.argv[1] if len(sys.argv) > 1 else find_data_dir()
    charts_dir = os.path.join(os.path.dirname(__file__), "charts")
    os.makedirs(charts_dir, exist_ok=True)

    print(f"Reading simulation CSVs from: {data_dir}")
    print(f"Saving charts to: {charts_dir}")

    messages_csv = os.path.join(data_dir, "messages.csv")
    summary_csv = os.path.join(data_dir, "summary.csv")
    convergence_csv = os.path.join(data_dir, "convergence.csv")

    if not os.path.exists(messages_csv):
        print(f"Error: Could not find {messages_csv}. Run the simulator first.")
        sys.exit(1)

    df_messages = pd.read_csv(messages_csv)
    print(f"Loaded {len(df_messages)} message records.")

    plt.style.use("seaborn-v0_8-whitegrid" if "seaborn-v0_8-whitegrid" in plt.style.available else "default")

    # 1. Delivery & ACK Rate by Hop Count
    if "hop_count" in df_messages.columns and len(df_messages) > 0:
        hop_stats = df_messages.groupby("hop_count").agg(
            total=("msg_id", "count"),
            delivered=("is_delivered", lambda x: (x == True).sum()),
            acked=("is_acked", lambda x: (x == True).sum())
        ).reset_index()

        hop_stats["delivery_rate"] = (hop_stats["delivered"] / hop_stats["total"]) * 100
        hop_stats["ack_rate"] = (hop_stats["acked"] / hop_stats["total"]) * 100

        fig, ax = plt.subplots(figsize=(8, 5))
        ax.plot(hop_stats["hop_count"], hop_stats["delivery_rate"], marker="o", linewidth=2, color="#2563eb", label="Delivery Rate (%)")
        ax.plot(hop_stats["hop_count"], hop_stats["ack_rate"], marker="s", linewidth=2, linestyle="--", color="#16a34a", label="ACK Rate (%)")
        ax.set_title("Message Delivery & ACK Rate vs Hop Count", fontsize=14, fontweight="bold", pad=12)
        ax.set_xlabel("Hop Count", fontsize=12)
        ax.set_ylabel("Success Rate (%)", fontsize=12)
        ax.set_ylim(-5, 105)
        ax.grid(True, linestyle=":", alpha=0.6)
        ax.legend(frameon=True)
        plt.tight_layout()
        delivery_chart = os.path.join(charts_dir, "delivery_vs_hops.png")
        plt.savefig(delivery_chart, dpi=300)
        plt.close()
        print(f"Saved: {delivery_chart}")

    # 2. End-to-End Latency vs Hop Count
    delivered_msgs = df_messages[(df_messages["is_delivered"] == True) & (df_messages["latency_ms"] >= 0)]
    if len(delivered_msgs) > 0:
        fig, ax = plt.subplots(figsize=(8, 5))
        avg_latency = delivered_msgs.groupby("hop_count")["latency_ms"].mean().reset_index()
        ax.bar(avg_latency["hop_count"], avg_latency["latency_ms"], color="#3b82f6", alpha=0.8, edgecolor="#1d4ed8", width=0.4)
        for _, row in avg_latency.iterrows():
            ax.text(row["hop_count"], row["latency_ms"] + 1, f"{row['latency_ms']:.1f} ms", ha="center", va="bottom", fontsize=10, fontweight="bold")

        ax.set_title("Average Latency vs Hop Count (Virtual Time)", fontsize=14, fontweight="bold", pad=12)
        ax.set_xlabel("Hop Count", fontsize=12)
        ax.set_ylabel("Latency (ms)", fontsize=12)
        ax.grid(True, axis="y", linestyle=":", alpha=0.6)
        plt.tight_layout()
        latency_chart = os.path.join(charts_dir, "latency_vs_hops.png")
        plt.savefig(latency_chart, dpi=300)
        plt.close()
        print(f"Saved: {latency_chart}")

    # 3. Routing Convergence / Recovery
    if os.path.exists(convergence_csv):
        df_conv = pd.read_csv(convergence_csv)
        if len(df_conv) > 0 and "convergence_time_ms" in df_conv.columns:
            fig, ax = plt.subplots(figsize=(8, 4))
            ax.barh(df_conv["description"], df_conv["convergence_time_ms"], color="#ef4444", alpha=0.85, edgecolor="#b91c1c", height=0.4)
            for idx, row in df_conv.iterrows():
                val = row["convergence_time_ms"]
                val_str = f"{val} ms" if val >= 0 else "N/A"
                ax.text(max(val, 0) + 1, idx, f" {val_str}", va="center", fontsize=10, fontweight="bold")

            ax.set_title("Routing Convergence Recovery Time", fontsize=14, fontweight="bold", pad=12)
            ax.set_xlabel("Recovery Time (ms)", fontsize=12)
            ax.grid(True, axis="x", linestyle=":", alpha=0.6)
            plt.tight_layout()
            conv_chart = os.path.join(charts_dir, "convergence_recovery.png")
            plt.savefig(conv_chart, dpi=300)
            plt.close()
            print(f"Saved: {conv_chart}")

    print("\nAll charts successfully generated in:", charts_dir)

if __name__ == "__main__":
    main()
