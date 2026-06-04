import json
import os

# List your input files
# Map input files to their desired output files
file_mapping = {
    "../data/output/verbalized_ontologies/FamilyOWL_1hop/Person_john_william_folland.json": "meta_data_family.json",
    "../data/output/verbalized_ontologies/OWL2Bench_1hop/AeronauticalEngineering_AeronauticalEngineering.json": "meta_data_owl2bench.json",
}

for input_file, output_file in file_mapping.items():
    # Load JSON
    with open(input_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Combine all class and property descriptions into one string
    all_descriptions = []

    for c in data.get("classes", []):
        desc = c.get("description", "")
        if desc:
            all_descriptions.append(desc)

    for p in data.get("objectProperties", []):
        desc = p.get("description", "")
        if desc:
            all_descriptions.append(desc)

    verbalization = " ".join(all_descriptions)  # single text block

    # Create new JSON
    new_json = {"metadata": {"verbalized_tbox": verbalization}}

    # Save to file
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(new_json, f, ensure_ascii=False, indent=2)

    print(f"Processed {input_file} -> {output_file}")
