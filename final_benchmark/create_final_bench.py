import pandas as pd
import json
import os
import argparse
import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parents[1]))


def verbalize_abox(json_data):
    output = []
    individuals = json_data.get("individuals", [])
    for ind in individuals:
        desc = ind.get("description", "")
        output.append(f"{desc}")
    output.append("")
    return "\n".join(output)


def parse_root_entity_and_get_verbalized_ont(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return verbalize_abox(data)
    except FileNotFoundError:
        return f"File not found: {path}"
    except json.JSONDecodeError:
        return f"Invalid JSON in file: {path}"
    except Exception as e:
        return f"Error processing {path}: {str(e)}"


def df_to_json(df):
    result = []

    group_cols = [
        "Task Type",
        "Answer Type",
        "Root Entity",
        "NL Context",
        "ABS Context",
    ]
    qa_cols = [
        "Task ID",
        "SPARQL Query",
        "NL Question",
        "ABS Question",
        "Answer",
        "Minimum Explanation",
        # "Explanation Count",
        # "Explanation Min",
        # "Explanation Max",
    ]

    grouped = df.groupby(group_cols)

    for group_keys, group_df in grouped:
        task_type, answer_type, root_entity, nl_context, abs_context = group_keys

        qas = group_df[qa_cols].to_dict(orient="records")

        entry = {
            "Task Type": task_type,
            "Answer Type": answer_type,
            "Root Entity": root_entity,
            "NL Context": nl_context,
            "ABS Context": abs_context,
            "QAs": qas,
        }

        result.append(entry)

    return result


def load_questions_file(base_path, filename_without_ext="SPARQL_questions_sampling_nl"):
    xlsx_path = os.path.join(base_path, f"{filename_without_ext}.xlsx")
    csv_path = os.path.join(base_path, f"{filename_without_ext}.csv")

    print("Checking:", os.path.abspath(xlsx_path))
    print("Checking:", os.path.abspath(csv_path))

    if os.path.exists(xlsx_path):
        print(f"Loading Excel file: {xlsx_path}")
        return pd.read_excel(xlsx_path)

    if os.path.exists(csv_path):
        print(f"Loading CSV file: {csv_path}")
        return pd.read_csv(csv_path)

    raise FileNotFoundError(f"Neither '{xlsx_path}' nor '{csv_path}' was found.")


def load_explanations(df, dataset, hop):
    explanations_file_path = os.path.join(
        "data", "output", dataset, hop, "Explanations.json"
    )

    from scripts.explanations_fix import repair_explanations_file

    output_path = repair_explanations_file(
        input_file=explanations_file_path,
        in_place=True,
    )

    print(output_path)

    with open(explanations_file_path, "r", encoding="utf-8") as f:
        explanations = json.load(f)

    lookup = {}

    for _, value in explanations.items():
        expl_list = value["explanations"]
        chosen_expl = min(expl_list, key=len) if expl_list else None

        for task_id in value["taskIds"]:
            lookup[task_id] = {
                "Minimum Explanation": chosen_expl,
                "Explanation Count": value["explanationCount"],
                "Explanation Min": value["size"]["min"],
                "Explanation Max": value["size"]["max"],
            }

    df = df.join(df["Task ID"].map(lookup).apply(pd.Series))
    return df


def process_dataset(dataset, hop):
    print(f"Processing dataset={dataset}, hop={hop}")

    base_path = os.path.join("data", "output", dataset, hop)

    q_nl = load_questions_file(base_path)
    q_abs = load_questions_file(
        base_path, filename_without_ext="SPARQL_questions_sampling_abs"
    )

    df = q_nl[
        [
            "Task ID",
            "Root Entity",
            "Task Type",
            "Answer Type",
            "SPARQL Query",
            "Question",
            "Answer",
        ]
    ].copy()

    df["NL Question"] = q_nl["Question"].values
    df["ABS Question"] = q_abs["Question"].values

    df = load_explanations(df, dataset, hop)

    verbalized_path = os.path.join(
        "data", "output", "verbalized_ontologies", f"{dataset}_{hop}"
    )

    verbalized_abs_path = os.path.join(
        "data", "output", "verbalized_ontologies", f"{dataset}_{hop}", "abstracted"
    )

    df["NL Context"] = df["Root Entity"].apply(
        lambda root: parse_root_entity_and_get_verbalized_ont(
            os.path.join(verbalized_path, f"{root}.json")
        )
    )

    df["ABS Context"] = df["Root Entity"].apply(
        lambda root: parse_root_entity_and_get_verbalized_ont(
            os.path.join(verbalized_abs_path, f"{root}.json")
        )
    )

    final_json = df_to_json(df)

    output_file = f"final_benchmark/{dataset}_{hop}.json"
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(final_json, f, indent=4, ensure_ascii=False)

    print(f"Saved → {output_file}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True, help="Dataset name, e.g. FamilyOWL")
    parser.add_argument("--hop", required=True, help="Hop value, e.g. 1hop or 2hop")

    args = parser.parse_args()

    process_dataset(args.dataset, args.hop)
