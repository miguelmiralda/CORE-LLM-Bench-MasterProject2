"""
stratified_sampling.py
---------------------
Stratified sampling of SPARQL questions for LLM pipeline.

Usage:
    python stratified_sampling.py input.csv output.csv
    python stratified_sampling.py input.csv output.csv --test-size 0.8 --random-state 42
"""

import argparse
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split


def stratified_sample(input_file, output_file, test_size=0.95, random_state=42):
    print(f"Reading input: {input_file}")
    df = pd.read_csv(input_file)
    print(f"Initial rows: {len(df)}")

    df = df.drop_duplicates()
    df = df[df["Task Type"].isin(["Membership", "Property Assertion"])].copy()
    df["Task ID temp"] = df["Task ID"].str.replace(r"-(BIN|MC)$", "", regex=True)

    # Bin variables
    bin_edges = np.histogram_bin_edges(df["Size of ontology ABox"], bins="auto")
    df["Bin_Size of ontology ABox"] = pd.cut(
        df["Size of ontology ABox"], bins=bin_edges, labels=False, include_lowest=True
    )

    bin_edges = np.histogram_bin_edges(df["Max Tag Length"], bins="auto")
    df["Bin_Max Tag Length"] = pd.cut(
        df["Max Tag Length"], bins=bin_edges, labels=False, include_lowest=True
    )

    # Combine bins into a single stratification key
    df["strata"] = (
        df["Bin_Size of ontology ABox"].astype(str)
        + "_"
        + df["Bin_Max Tag Length"].astype(str)
    )

    # Group by Task ID temp
    df_groups = df.groupby("Task ID temp").first().reset_index()
    strata_counts = df_groups["strata"].value_counts()
    valid_strata = strata_counts[strata_counts >= 2].index
    df_filtered = df_groups[df_groups["strata"].isin(valid_strata)].copy()

    n_groups = len(df_filtered)
    n_classes = df_filtered["strata"].nunique()

    print(f"Groups after filtering: {n_groups}")
    print(f"Number of strata: {n_classes}")

    if n_groups == 0:
        raise ValueError(
            "No valid groups remain after filtering strata with at least 2 samples."
        )

    if n_groups < 2 * n_classes:
        raise ValueError(
            f"Stratified split impossible: {n_groups} groups for {n_classes} strata. "
            f"Need at least {2 * n_classes} groups total."
        )

    requested_test_size = int(round(test_size * n_groups))

    # Ensure both splits can contain all strata
    min_test_size = n_classes
    max_test_size = n_groups - n_classes
    adjusted_test_size = min(max(requested_test_size, min_test_size), max_test_size)
    adjusted_train_size = n_groups - adjusted_test_size

    if adjusted_test_size != requested_test_size:
        print(
            f"Adjusted test size from {requested_test_size} to {adjusted_test_size} "
            f"so both splits contain at least one sample per stratum."
        )

    train_groups, test_groups = train_test_split(
        df_filtered["Task ID temp"],
        train_size=adjusted_train_size,
        test_size=adjusted_test_size,
        stratify=df_filtered["strata"],
        random_state=random_state,
    )

    # Assign split back to original df rows based on group membership
    df["split"] = "test"
    df.loc[df["Task ID temp"].isin(train_groups), "split"] = "train"

    train_df = df[df["split"] == "train"].copy()

    columns_to_drop = [
        "Task ID temp",
        "Bin_Size of ontology ABox",
        "Bin_Max Tag Length",
        "strata",
        "split",
    ]
    train_df = train_df.drop(columns=columns_to_drop)

    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"Sampled rows: {len(train_df)}")
    print(f"Writing output: {output_file}")
    train_df.to_csv(output_file, index=False)
    print("Done.")
    return train_df


def main():
    parser = argparse.ArgumentParser(
        description="Perform stratified sampling on a SPARQL questions CSV file."
    )
    parser.add_argument("--input_file", type=str, help="Path to input CSV file")
    parser.add_argument("--output_file", type=str, help="Path to output CSV file")
    parser.add_argument(
        "--test-size",
        type=float,
        default=0.95,
        help="Fraction assigned to test split (default: 0.95)",
    )
    parser.add_argument(
        "--random-state",
        type=int,
        default=42,
        help="Random seed (default: 42)",
    )

    args = parser.parse_args()

    stratified_sample(
        input_file=args.input_file,
        output_file=args.output_file,
        test_size=args.test_size,
        random_state=args.random_state,
    )


if __name__ == "__main__":
    main()
