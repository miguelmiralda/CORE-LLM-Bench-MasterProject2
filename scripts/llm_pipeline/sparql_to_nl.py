import os
import time
import sys
import pandas as pd
from datetime import datetime
import openai
from dotenv import load_dotenv
from tqdm import tqdm
import logging
from pathlib import Path
import argparse

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
    handlers=[logging.FileHandler("processing.log"), logging.StreamHandler()],
)
logger = logging.getLogger(__name__)

load_dotenv()

CONFIG = {
    "temperature": 0.0,
    "max_tokens": 4096,
    "top_p": 1.0,
    "presence_penalty": 0.0,
    "frequency_penalty": 0.0,
    "checkpoint_frequency": 50,
    "api_delay": 0.1,
    "max_retries": 3,
    "retry_delay": 2.0,
}

openai_api_key = os.getenv("OPENAI_API_KEY")
if not openai_api_key:
    raise ValueError("OPENAI_API_KEY not found in environment variables")

client = openai.OpenAI(api_key=openai_api_key)

PROMPT_TEMPLATES = {
    "binary": """Convert the following SPARQL query into a natural-language statement for non-technical humans. Do not use SPARQL or ontology jargon. Do not include birth dates or birth years unless they are essential.
Use the {language_string} language. SPARQL query:
{sparql}""",
    "membership": """Convert the following SPARQL query into a natural-language statement for non-technical humans. Describe which class or classes the individual belongs to, without using technical phrases like 'rdf:type' or 'type of entity'. Do not include birth dates or birth years unless they are essential.
Use the {language_string} language. SPARQL query:
{sparql}""",
    "property": """Convert the following SPARQL query into a natural-language statement for non-technical humans. Describe the relationship or attribute involved, and avoid SPARQL or ontology jargon. Do not include birth dates or birth years unless they are essential.
Use the {language_string} language. SPARQL query:
{sparql}""",
}


def parse_args():
    parser = argparse.ArgumentParser(
        description="Generate natural-language questions from SPARQL queries."
    )
    parser.add_argument(
        "--input-csv",
        type=str,
        required=True,
        help="Path to the input CSV file.",
    )
    parser.add_argument(
        "--output-directory",
        type=str,
        required=True,
        help="Path to the output directory.",
    )
    parser.add_argument(
        "--output-file",
        type=str,
        required=False,
        help="Name of the output CSV file.",
    )
    parser.add_argument(
        "--model",
        type=str,
        required=True,
        help="OpenAI model name to use.",
    )
    return parser.parse_args()


def get_model_version_info(response):
    model_version = "Unknown"
    if hasattr(response, "model"):
        model_version = response.model
    if hasattr(response, "system_fingerprint"):
        model_version += f" (fingerprint: {response.system_fingerprint})"
    return model_version


def normalize_answer_type(answer_type):
    value = str(answer_type).strip().lower()

    mapping = {
        "bin": "binary",
        "binary": "binary",
        "true/false": "binary",
        "true false": "binary",
        "mc": "multi choice",
        "multi choice": "multi choice",
        "multiple choice": "multi choice",
        "multichoice": "multi choice",
    }

    if value in mapping:
        return mapping[value]

    raise ValueError(f"Unknown Answer Type: {answer_type}")


def get_prompt_template(answer_type, task_type=None):
    normalized_answer_type = normalize_answer_type(answer_type)
    normalized_task_type = str(task_type).strip().lower() if task_type else ""

    if normalized_answer_type == "binary":
        return PROMPT_TEMPLATES["binary"]
    elif normalized_answer_type == "multi choice":
        if normalized_task_type == "membership":
            return PROMPT_TEMPLATES["membership"]
        return PROMPT_TEMPLATES["property"]
    else:
        raise ValueError(f"Unknown Answer Type: {answer_type}")


def is_permanent_model_error(error):
    error_text = str(error).lower()
    permanent_markers = [
        "model_not_found",
        "does not have access to model",
        "invalid_request_error",
        "403",
    ]
    return any(marker in error_text for marker in permanent_markers)


def make_api_call_with_retry(client, model_name, messages, gen_params, max_retries=3):
    for attempt in range(max_retries):
        try:
            response = client.chat.completions.create(
                model=model_name,
                messages=messages,
                **gen_params,
            )
            return response.choices[0].message.content, get_model_version_info(response)

        except Exception as e:
            logger.warning(f"API call attempt {attempt + 1} failed: {str(e)}")

            if is_permanent_model_error(e):
                logger.error("Permanent API error detected; not retrying")
                return f"[ERROR] {str(e)}", "Error - Unable to retrieve"

            if attempt < max_retries - 1:
                time.sleep(CONFIG["retry_delay"] * (attempt + 1))
            else:
                logger.error(f"All {max_retries} API call attempts failed")
                return f"[ERROR] {str(e)}", "Error - Unable to retrieve"


def validate_dataframe(df):
    required_columns = ["SPARQL Query"]
    missing_cols = [col for col in required_columns if col not in df.columns]
    if missing_cols:
        raise ValueError(f"CSV must contain columns: {missing_cols}")

    df["Language"] = df.get("Language", "English")
    df["Answer Type"] = df.get("Answer Type", "Binary").astype(str).str.strip()
    df["Task Type"] = df.get("Task Type", "").astype(str).str.strip()

    logger.info(f"Loaded DataFrame with {len(df)} rows")
    logger.info(f"Unique Answer Types: {df['Answer Type'].unique().tolist()}")
    return df


def save_checkpoint(df, logs, checkpoint_dir, checkpoint_num):
    checkpoint_dir.mkdir(parents=True, exist_ok=True)

    df.to_csv(
        checkpoint_dir / f"checkpoint_{checkpoint_num}_questions.csv", index=False
    )
    pd.DataFrame(logs).to_csv(
        checkpoint_dir / f"checkpoint_{checkpoint_num}_log.csv", index=False
    )
    logger.info(f"Checkpoint {checkpoint_num} saved")


def create_output_directory(output_directory):
    output_dir = Path(output_directory)
    output_dir.mkdir(parents=True, exist_ok=True)
    return output_dir


def main():
    args = parse_args()

    try:
        df = pd.read_csv(args.input_csv)
        df = validate_dataframe(df)
    except FileNotFoundError:
        logger.error(f"Input CSV file not found: {args.input_csv}")
        return
    except Exception as e:
        logger.error(f"Error loading CSV: {str(e)}")
        return

    output_dir = create_output_directory(args.output_directory)
    checkpoint_dir = output_dir / "checkpoints"
    logs = []

    stats = {"total": len(df), "processed": 0, "errors": 0, "start_time": time.time()}

    for idx, row in tqdm(
        df.iterrows(), total=len(df), desc="Processing SPARQL queries"
    ):
        try:
            sparql = row["SPARQL Query"]
            language_string = row.get("Language", "English")
            answer_type = normalize_answer_type(row.get("Answer Type", "Binary"))
            task_type = row.get("Task Type", "")

            prompt_template = get_prompt_template(answer_type, task_type)
            prompt = prompt_template.format(
                language_string=language_string, sparql=sparql
            )

            print(f"\n🔎 Processing question #{idx + 1} in {language_string}")

            start_time = time.time()
            messages = [{"role": "user", "content": prompt}]
            gen_params = {
                k: v
                for k, v in CONFIG.items()
                if k
                in [
                    "temperature",
                    "max_tokens",
                    "top_p",
                    "presence_penalty",
                    "frequency_penalty",
                ]
            }

            content, model_version = make_api_call_with_retry(
                client,
                args.model,
                messages,
                gen_params,
                CONFIG["max_retries"],
            )

            end_time = time.time()

            if content.startswith("[ERROR]"):
                print(f"❌ Error: {content}")
                stats["errors"] += 1
            else:
                print(f"✅ Generated Question: {content}")
                stats["processed"] += 1

            df.at[idx, "Question"] = content

            log_entry = {
                "SPARQL_index": idx,
                "SPARQL_query": sparql,
                "generated_question": content,
                "language": language_string,
                "answer_type": answer_type,
                "task_type": task_type,
                "model": args.model,
                "model_version": model_version,
                "python_version": sys.version.split()[0],
                "timestamp_request": datetime.now().isoformat(),
                "response_time_sec": round(end_time - start_time, 3),
                "response_preview": content[:100],
                "success": not content.startswith("[ERROR]"),
            }

            log_entry.update(gen_params)
            logs.append(log_entry)

            time.sleep(CONFIG["api_delay"])

        except Exception as e:
            logger.error(f"Error processing row {idx}: {str(e)}")
            df.at[idx, "Question"] = f"[ERROR] {str(e)}"
            stats["errors"] += 1

        if (idx + 1) % CONFIG["checkpoint_frequency"] == 0:
            save_checkpoint(
                df,
                logs,
                checkpoint_dir,
                (idx + 1) // CONFIG["checkpoint_frequency"],
            )

    output_filename = output_dir / args.output_file
    log_filename = output_dir / "processing_log_sparql_to_nl_abs.csv"
    stats_filename = output_dir / "processing_stats_sparql_to_nl_abs.txt"

    df.to_csv(output_filename, index=False)
    pd.DataFrame(logs).to_csv(log_filename, index=False)

    total_time = time.time() - stats["start_time"]
    with open(stats_filename, "w", encoding="utf-8") as f:
        f.write("Processing Statistics\n")
        f.write("====================\n")
        f.write(f"Total questions: {stats['total']}\n")
        f.write(f"Successfully processed: {stats['processed']}\n")
        f.write(f"Errors: {stats['errors']}\n")
        f.write(f"Success rate: {stats['processed'] / stats['total'] * 100:.1f}%\n")
        f.write(f"Total processing time: {total_time:.2f} seconds\n")
        f.write(
            f"Average time per question: {total_time / stats['total']:.2f} seconds\n"
        )

    print(f"\n✅ Done! Results saved to {output_filename}")
    print(
        f"📊 Success rate: {stats['processed']}/{stats['total']} ({stats['processed'] / stats['total'] * 100:.1f}%)"
    )

    logger.info(
        f"Processing completed. Success rate: {stats['processed']}/{stats['total']}"
    )


if __name__ == "__main__":
    main()
