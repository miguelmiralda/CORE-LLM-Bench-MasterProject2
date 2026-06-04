# usage.py
from OntologyAbstractor import process_ontology_abstraction
from pathlib import Path
import os
import argparse

# Navigate to project root
script_dir = Path(__file__).resolve().parent
project_root = script_dir.parent.parent.parent
os.chdir(project_root)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Run ontology abstraction on a directory of ontologies."
    )
    parser.add_argument(
        "--input-directory",
        type=str,
        required=True,
        help="Path to the input ontology directory.",
    )
    parser.add_argument(
        "--output-directory",
        type=str,
        required=True,
        help="Path to the output directory.",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    results = process_ontology_abstraction(
        ontology_dir=args.input_directory,
        output_dir=args.output_directory,
    )

    print("Comprehensive abstraction completed!")
    print(f"Abstracted ontologies: {results['ontologies_dir']}")
    print(f"Mappings: {results['mappings_file']}")


if __name__ == "__main__":
    main()
