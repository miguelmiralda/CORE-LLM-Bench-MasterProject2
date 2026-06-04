import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd


PAIR_KEY_COLUMNS = [
    "Root Entity",
    "Normalized Task Type",
    "Answer Type",
    "SPARQL Query",
    "Predicate",
]

REQUIRED_COLUMNS = PAIR_KEY_COLUMNS + [
    "Task Type",
    "Task ID",
    "Entailment Label",
    "Size of ontology ABox",
    "Max Tag Length",
]
REQUIRED_COLUMNS = [column for column in REQUIRED_COLUMNS if column != "Normalized Task Type"]


def normalize_label(value):
    return str(value).strip().upper()


def make_pair_key(df):
    return (
        df[PAIR_KEY_COLUMNS]
        .fillna("")
        .astype(str)
        .agg("||".join, axis=1)
    )


def normalize_task_type(value):
    value = str(value).strip()
    if value.startswith("Membership"):
        return "Membership"
    if value.startswith("Property Assertion"):
        return "Property Assertion"
    return value


def add_numeric_bins(df, column, output_column):
    values = pd.to_numeric(df[column], errors="coerce").fillna(0)
    if values.nunique() <= 1:
        df[output_column] = "0"
        return

    try:
        df[output_column] = pd.qcut(values, q=min(5, values.nunique()), labels=False, duplicates="drop")
    except ValueError:
        df[output_column] = pd.cut(values, bins="auto", labels=False, include_lowest=True)

    df[output_column] = df[output_column].fillna(0).astype(int).astype(str)


def allocate_stratified_counts(strata_counts, target_size):
    target_size = min(target_size, int(strata_counts.sum()))
    target_size = max(target_size, len(strata_counts))

    raw = strata_counts * (target_size / strata_counts.sum())
    allocated = np.floor(raw).astype(int)
    allocated[allocated == 0] = 1
    allocated = np.minimum(allocated, strata_counts)

    while allocated.sum() > target_size:
        reducible = allocated[allocated > 1]
        if reducible.empty:
            break
        idx = reducible.sort_values(ascending=False).index[0]
        allocated.loc[idx] -= 1

    while allocated.sum() < target_size:
        capacity = strata_counts - allocated
        available = capacity[capacity > 0]
        if available.empty:
            break
        remainders = (raw - np.floor(raw)).reindex(available.index).fillna(0)
        idx = remainders.sort_values(ascending=False).index[0]
        allocated.loc[idx] += 1

    return allocated.astype(int)


def fair_sample(input_file, output_directory, sample_fraction=0.1, random_state=42, max_pairs=None):
    input_path = Path(input_file)
    output_dir = Path(output_directory)
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Reading input: {input_path}")
    df = pd.read_csv(input_path).drop_duplicates()
    print(f"Initial rows: {len(df)}")

    missing = [column for column in REQUIRED_COLUMNS if column not in df.columns]
    if missing:
        raise ValueError(f"Input CSV is missing required columns: {missing}")

    df = df[
        df["Task Type"].astype(str).str.startswith(("Membership", "Property Assertion"))
    ].copy()
    df["Entailment Label"] = df["Entailment Label"].map(normalize_label)
    df["Answer Type"] = df["Answer Type"].astype(str).str.strip().str.upper()
    df["Normalized Task Type"] = df["Task Type"].map(normalize_task_type)
    
    df = df[
        (df["Answer Type"] == "BIN")
        & (df["Entailment Label"].isin(["ENTAILED", "NON_ENTAILED"]))
    ].copy()

    df["Pair Key"] = make_pair_key(df)

    entailed = (
        df[df["Entailment Label"] == "ENTAILED"]
        .drop_duplicates("Pair Key")
        .copy()
    )
    non_entailed = (
        df[df["Entailment Label"] == "NON_ENTAILED"]
        .drop_duplicates("Pair Key")
        .copy()
    )

    common_keys = sorted(set(entailed["Pair Key"]) & set(non_entailed["Pair Key"]))
    print(f"Matched binary pairs: {len(common_keys)}")
    if not common_keys:
        raise ValueError("No matched ENTAILED/NON_ENTAILED binary pairs were found.")

    pair_frame = entailed[entailed["Pair Key"].isin(common_keys)].copy()
    add_numeric_bins(pair_frame, "Size of ontology ABox", "ABox Bin")
    add_numeric_bins(pair_frame, "Max Tag Length", "Max Tag Bin")
    pair_frame["Stratum"] = (
        pair_frame["Normalized Task Type"].astype(str)
        + "|abox="
        + pair_frame["ABox Bin"].astype(str)
        + "|tag="
        + pair_frame["Max Tag Bin"].astype(str)
    )

    total_pairs = len(pair_frame)
    target_pairs = max(1, int(round(total_pairs * sample_fraction)))
    if max_pairs is not None:
        target_pairs = min(target_pairs, max_pairs)

    strata_counts = pair_frame["Stratum"].value_counts().sort_index()
    target_pairs = min(total_pairs, max(target_pairs, len(strata_counts)))
    allocations = allocate_stratified_counts(strata_counts, target_pairs)

    rng = np.random.default_rng(random_state)
    selected_keys = []
    for stratum, count in allocations.items():
        group = pair_frame[pair_frame["Stratum"] == stratum]
        seed = int(rng.integers(0, 2**32 - 1))
        selected = group.sample(n=int(count), random_state=seed)
        selected_keys.extend(selected["Pair Key"].tolist())

    selected_key_set = set(selected_keys)
    entailed_sample = entailed[entailed["Pair Key"].isin(selected_key_set)].copy()
    non_entailed_sample = non_entailed[non_entailed["Pair Key"].isin(selected_key_set)].copy()
    combined_sample = pd.concat([entailed_sample, non_entailed_sample], ignore_index=True)

    sort_columns = ["Pair Key", "Entailment Label", "Task ID"]
    entailed_sample = entailed_sample.sort_values(sort_columns)
    non_entailed_sample = non_entailed_sample.sort_values(sort_columns)
    combined_sample = combined_sample.sort_values(sort_columns)

    drop_columns = ["Pair Key", "Normalized Task Type"]
    entailed_sample = entailed_sample.drop(columns=drop_columns)
    non_entailed_sample = non_entailed_sample.drop(columns=drop_columns)
    combined_sample = combined_sample.drop(columns=drop_columns)

    outputs = {
        "entailed": output_dir / "SPARQL_questions_entailed_sampling.csv",
        "non_entailed": output_dir / "SPARQL_questions_non_entailed_sampling.csv",
        "combined": output_dir / "SPARQL_questions_combined_sampling.csv",
    }

    entailed_sample.to_csv(outputs["entailed"], index=False)
    non_entailed_sample.to_csv(outputs["non_entailed"], index=False)
    combined_sample.to_csv(outputs["combined"], index=False)

    metadata = {
        "input_file": str(input_path),
        "sample_fraction": sample_fraction,
        "random_state": random_state,
        "matched_pairs_available": total_pairs,
        "matched_pairs_sampled": len(selected_key_set),
        "entailed_rows": len(entailed_sample),
        "non_entailed_rows": len(non_entailed_sample),
        "combined_rows": len(combined_sample),
        "strata": allocations.astype(int).to_dict(),
        "outputs": {key: str(value) for key, value in outputs.items()},
    }

    metadata_path = output_dir / "fair_sampling_metadata.json"
    metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")

    print(f"Sampled matched pairs: {len(selected_key_set)}")
    print(f"Wrote entailed sample: {outputs['entailed']}")
    print(f"Wrote non-entailed sample: {outputs['non_entailed']}")
    print(f"Wrote combined sample: {outputs['combined']}")
    print(f"Wrote metadata: {metadata_path}")

    return metadata


def main():
    parser = argparse.ArgumentParser(
        description="Create matched benchmark samples for entailed/non-entailed comparisons."
    )
    parser.add_argument("--input-file", required=True, help="Path to SPARQL_questions.csv")
    parser.add_argument("--output-directory", required=True, help="Benchmark output directory")
    parser.add_argument(
        "--sample-fraction",
        type=float,
        default=0.05,
        help="Fraction of matched pairs to sample (default: 0.05)",
    )
    parser.add_argument(
        "--random-state",
        type=int,
        default=42,
        help="Random seed (default: 42)",
    )
    parser.add_argument(
        "--max-pairs",
        type=int,
        default=None,
        help="Optional cap on sampled matched pairs.",
    )

    args = parser.parse_args()
    fair_sample(
        input_file=args.input_file,
        output_directory=args.output_directory,
        sample_fraction=args.sample_fraction,
        random_state=args.random_state,
        max_pairs=args.max_pairs,
    )


if __name__ == "__main__":
    main()
