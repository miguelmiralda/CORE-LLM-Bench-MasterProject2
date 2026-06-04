import argparse
from pathlib import Path
import json
import re


def fix_explanations_json(text: str) -> str:
    fixed = text

    # Remove lines that contain only a comma
    fixed = re.sub(r"^[ \t]*,[ \t]*\r?\n", "", fixed, flags=re.MULTILINE)

    # Remove duplicate commas like ",,"
    fixed = re.sub(r",\s*,", ",", fixed)

    # Remove trailing commas before } or ]
    fixed = re.sub(r",(\s*[}\]])", r"\1", fixed)

    # If file starts with { but does not end with }, try closing it
    if fixed.lstrip().startswith("{") and not fixed.rstrip().endswith("}"):
        fixed = fixed.rstrip() + "\n}"

    return fixed


def repair_explanations_file(
    input_file: str | Path,
    output_file: str | Path | None = None,
    in_place: bool = False,
    validate: bool = True,
) -> Path:
    input_path = Path(input_file)

    if not input_path.exists():
        raise FileNotFoundError(f"Input file does not exist: {input_path}")

    if in_place and output_file is not None:
        raise ValueError("Use either in_place=True or output_file, not both.")

    if in_place:
        output_path = input_path
    elif output_file is not None:
        output_path = Path(output_file)
    else:
        output_path = input_path.with_name(
            f"{input_path.stem}_fixed{input_path.suffix}"
        )

    text = input_path.read_text(encoding="utf-8")
    fixed = fix_explanations_json(text)

    if validate:
        json.loads(fixed)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(fixed, encoding="utf-8")

    return output_path


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Repair malformed Explanations.json files produced by a buggy writer."
    )
    parser.add_argument(
        "--input-file", required=True, help="Path to the input JSON file"
    )
    parser.add_argument("--output-file", help="Path to the repaired output file")
    parser.add_argument(
        "--in-place",
        action="store_true",
        help="Overwrite the input file with the repaired JSON",
    )
    parser.add_argument(
        "--no-validate",
        action="store_true",
        help="Skip json.loads validation before writing",
    )

    args = parser.parse_args()

    output_path = repair_explanations_file(
        input_file=args.input_file,
        output_file=args.output_file,
        in_place=args.in_place,
        validate=not args.no_validate,
    )

    print(f"Wrote repaired JSON to: {output_path}")


if __name__ == "__main__":
    main()
