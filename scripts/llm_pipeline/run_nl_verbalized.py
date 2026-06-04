"""
Natural Language + Verbalized Ontologies: Tests LLM reasoning with human-readable questions and context
Enhanced version with improved memory management for heavy ontologies and thousands of questions
"""

import pandas as pd
import os
import time
import json
import gc
import psutil
import argparse
from pathlib import Path
from datetime import datetime
from api_calls import (
    run_llm_reasoning,
    log_models_metadata,
    calculate_model_performance_summary,
    check_api_clients,
    openai_client,
    deepseek_client,
    openrouter_client,
)

# Navigate to project root
script_dir = Path(__file__).resolve().parent
project_root = script_dir.parent.parent
os.chdir(project_root)

print(f"Working directory set to: {os.getcwd()}")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Run NL + verbalized ontology reasoning experiment."
    )
    parser.add_argument(
        "--questions-csv",
        type=str,
        required=True,
        help="Path to the questions CSV file.",
    )
    parser.add_argument(
        "--verbalized-ontology-dir",
        type=str,
        required=True,
        help="Path to the directory containing verbalized ontology JSON files.",
    )
    parser.add_argument(
        "--output-directory",
        type=str,
        required=True,
        help="Path to the output directory.",
    )
    parser.add_argument(
        "--abstracted",
        action="store_true",
        help="If set, append 'abs_' to the final CSV filename.",
    )
    parser.add_argument(
        "--models",
        nargs="+",
        required=True,
        help=(
            "List of models in the format provider:model_id. "
            "Example: openai:gpt-4.1-mini openrouter:deepseek/deepseek-chat "
            "openrouter:meta-llama/llama-4-maverick"
        ),
    )
    parser.add_argument(
        "--question-column",
        type=str,
        default="Question",
        help="Column name containing the natural language question.",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=8,
        help="Maximum number of workers.",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=25,
        help="Batch size for processing.",
    )
    parser.add_argument(
        "--checkpoint-frequency",
        type=int,
        default=50,
        help="Checkpoint frequency.",
    )
    parser.add_argument(
        "--memory-threshold",
        type=int,
        default=85,
        help="Memory threshold percentage for cleanup warnings.",
    )
    parser.add_argument(
        "--silent-mode",
        action="store_true",
        help="Enable silent mode.",
    )
    parser.add_argument(
        "--test-mode",
        action="store_true",
        help="Enable test mode (process first 50 questions only).",
    )
    return parser.parse_args()


def parse_models(model_args):
    models = {}
    for item in model_args:
        if ":" not in item:
            raise ValueError(
                f"Invalid model specification '{item}'. Use provider:model_id"
            )

        provider, model_id = item.split(":", 1)
        provider = provider.strip().lower()
        model_id = model_id.strip()

        if provider not in {"openai", "openrouter", "deepseek"}:
            raise ValueError(
                f"Unsupported provider '{provider}' in '{item}'. "
                f"Supported: openai, openrouter, deepseek"
            )

        display_name = f"{provider}_{model_id.replace('/', '_').replace('-', '_')}"
        models[display_name] = {
            "provider": provider,
            "model_id": model_id,
        }

    return models


def monitor_memory():
    return psutil.virtual_memory().percent


def force_cleanup():
    gc.collect()
    time.sleep(1)


def build_config(args):
    models_config = parse_models(args.models)

    return {
        "experiment_type": "nl_verbalized",
        "description": "Natural language questions with verbalized JSON ontology context - Memory Optimized",
        "questions_csv": args.questions_csv,
        "verbalized_ontology_dir": args.verbalized_ontology_dir,
        "abstracted": args.abstracted,
        "context_mode": "json",
        "question_column": args.question_column,
        "models_used": models_config,
        "max_workers": args.max_workers,
        "batch_size": args.batch_size,
        "checkpoint_frequency": args.checkpoint_frequency,
        "silent_mode": args.silent_mode,
        "test_mode": args.test_mode,
        "memory_threshold": args.memory_threshold,
    }


def print_experiment_header(config, output_dir):
    print("\n" + "=" * 80)
    print("🚀 NATURAL LANGUAGE + VERBALIZED EXPERIMENT (MEMORY OPTIMIZED)")
    print("=" * 80)
    print(f"📋 Experiment: {config['description']}")
    print(f"🤖 Models: {', '.join(config['models_used'].keys())}")
    print(f"🔧 Max Workers: {config['max_workers']}")
    print(f"📦 Batch Size: {config['batch_size']}")
    print(f"💾 Checkpoint: Every {config['checkpoint_frequency']} questions")
    print(f"🔇 Silent Mode: {'ON' if config['silent_mode'] else 'OFF'}")
    print(f"🧠 Memory Threshold: {config['memory_threshold']}%")
    print(f"📂 Output: {output_dir}")
    print("=" * 80)


def validate_setup(config):
    print("🔍 Validating setup...")
    print(f"🧠 Initial memory usage: {monitor_memory():.1f}%")

    try:
        df = pd.read_csv(config["questions_csv"])
        print(f"✅ Loaded {len(df)} questions from {config['questions_csv']}")
    except FileNotFoundError:
        print(f"❌ Error: Questions CSV not found at {config['questions_csv']}")
        return None

    verbalized_dir = Path(config["verbalized_ontology_dir"])
    if not verbalized_dir.exists():
        print(
            f"❌ Error: Verbalized ontology directory does not exist: {verbalized_dir}"
        )
        return None

    available_json_files = {p.stem for p in verbalized_dir.glob("*.json")}
    original_count = len(df)
    df = df[df["Root Entity"].isin(available_json_files)].copy()

    print(f"🔍 Found {len(available_json_files)} JSON ontology files")
    print(f"📢 Filtered dataset to {len(df)} questions (from {original_count})")

    if df.empty:
        print(
            "❌ Error: No questions remain after filtering. Check JSON file names and 'Root Entity' column."
        )
        return None

    total_size = 0
    large_ontologies = 0
    for json_file in verbalized_dir.glob("*.json"):
        size = json_file.stat().st_size
        total_size += size
        if size > 100000:
            large_ontologies += 1

    print("📊 Ontology Analysis:")
    print(f"   Total ontology data: {total_size / 1024 / 1024:.1f} MB")
    print(f"   Large ontologies (>100KB): {large_ontologies}")
    print(f"   Average size: {total_size / len(available_json_files) / 1024:.1f} KB")

    if total_size > 500 * 1024 * 1024:
        print(
            "⚠️ Warning: Large ontology dataset detected. Consider reducing batch size."
        )

    return df


def analyze_nl_patterns(df):
    patterns = {
        "binary_questions": 0,
        "multi_choice_questions": 0,
        "membership_questions": 0,
        "property_questions": 0,
        "complex_questions": 0,
        "short_questions": 0,
        "medium_questions": 0,
        "long_questions": 0,
    }

    for _, row in df.iterrows():
        question = str(row.get("Question", "")).lower()
        answer_type = str(row.get("Answer Type", "")).lower()
        task_type = str(row.get("Task Type", "")).lower()

        if answer_type == "bin":
            patterns["binary_questions"] += 1
        elif answer_type == "mc":
            patterns["multi_choice_questions"] += 1

        if "membership" in task_type:
            patterns["membership_questions"] += 1
        elif "property" in task_type:
            patterns["property_questions"] += 1

        word_count = len(question.split())
        if word_count <= 5:
            patterns["short_questions"] += 1
        elif word_count <= 10:
            patterns["medium_questions"] += 1
        else:
            patterns["long_questions"] += 1

        if " and " in question or " or " in question or "?" in question[:-1]:
            patterns["complex_questions"] += 1

    return patterns


def estimate_experiment_time(
    df, models_config, max_workers, checkpoint_frequency, batch_size
):
    estimated_time_per_question = 3.0
    total_calls = len(df) * len(models_config)
    estimated_total_time = (total_calls * estimated_time_per_question) / max_workers

    print(f"\n⏱️ EXPERIMENT ESTIMATES:")
    print(f"   Total questions: {len(df)}")
    print(f"   Total API calls: {total_calls}")
    print(f"   Estimated time: {estimated_total_time / 60:.1f} minutes")
    print(f"   Checkpoints will be saved every {checkpoint_frequency} questions")
    print(f"   Memory monitoring: Every {batch_size} questions")
    return estimated_total_time


def check_for_previous_run(output_dir):
    recovery_file = output_dir / "LATEST_recovery_info.json"
    if recovery_file.exists():
        try:
            with open(recovery_file, "r") as f:
                recovery_info = json.load(f)

            print(f"\n🔄 Found previous incomplete run:")
            print(
                f"   Completed: {recovery_info['questions_completed']}/{recovery_info['total_questions']} questions"
            )
            print(f"   Progress: {recovery_info['completion_percentage']:.1f}%")
            if "memory_usage_percent" in recovery_info:
                print(
                    f"   Last memory usage: {recovery_info['memory_usage_percent']:.1f}%"
                )

            response = (
                input("Do you want to continue from where you left off? (y/n): ")
                .lower()
                .strip()
            )
            if response == "y":
                latest_csv = output_dir / "LATEST_checkpoint.csv"
                if latest_csv.exists():
                    df = pd.read_csv(latest_csv)
                    return df, recovery_info

        except Exception as e:
            print(f"⚠️ Could not load previous run: {e}")

    return None, None


def print_completion_summary(
    results_df, actual_time, estimated_time, nl_patterns, models_config
):
    print("\n" + "=" * 80)
    print("🎉 NATURAL LANGUAGE EXPERIMENT COMPLETED!")
    print("=" * 80)

    print("⏱️ Time Analysis:")
    print(f"   Actual time: {actual_time:.1f}s ({actual_time / 60:.1f}min)")
    print(f"   Estimated time: {estimated_time:.1f}s ({estimated_time / 60:.1f}min)")
    time_diff = ((actual_time - estimated_time) / estimated_time) * 100
    print(f"   Difference: {time_diff:+.1f}%")

    final_memory = monitor_memory()
    print("🧠 Memory Analysis:")
    print(f"   Final memory usage: {final_memory:.1f}%")

    total_questions = len(results_df)
    print(f"\n📊 Response Analysis:")
    print(f"   Total questions: {total_questions}")

    for model in models_config.keys():
        response_col = f"{model}_response"
        if response_col in results_df.columns:
            responses = results_df[response_col]
            non_empty = (responses != "").sum()
            errors = responses.astype(str).str.startswith("[ERROR]").sum()
            success = non_empty - errors
            success_rate = (success / len(responses)) * 100 if len(responses) > 0 else 0

            correctness_col = f"{model}_quality_correctness"
            if correctness_col in results_df.columns:
                correct_answers = (results_df[correctness_col] > 0.5).sum()
                correctness_rate = (correct_answers / len(results_df)) * 100
                avg_correctness = results_df[correctness_col].mean()
                avg_response_time = pd.to_numeric(
                    results_df[f"{model}_response_time"], errors="coerce"
                ).mean()

                print(f"   🤖 {model}:")
                print(
                    f"     Completed: {success}/{len(responses)} ({success_rate:.1f}%)"
                )
                print(
                    f"     Correct: {correct_answers}/{len(results_df)} ({correctness_rate:.1f}%)"
                )
                print(f"     Avg Correctness: {avg_correctness:.3f}")
                print(f"     Avg Response Time: {avg_response_time:.2f}s")
                if errors > 0:
                    print(f"     Errors: {errors}")


def save_final_results(
    results_df,
    logs,
    detailed_metrics,
    experiment_time,
    nl_patterns,
    config,
    output_dir,
    models_config,
):
    print(f"\n💾 Saving final results...")

    suffix = "abs" if config.get("abstracted", False) else ""
    results_file = output_dir / f"{suffix}_nl_verbalized_results_FINAL.csv"
    logs_file = output_dir / f"{suffix}_nl_verbalized_logs_FINAL.csv"
    metrics_file = output_dir / f"{suffix}_nl_verbalized_metrics_FINAL.json"
    config_file = output_dir / f"{suffix}_experiment_summary.json"

    results_df.to_csv(results_file, index=False)

    if len(logs) > 1000:
        logs_df = pd.DataFrame(logs)
        logs_df.to_csv(logs_file, index=False, chunksize=500)
        del logs_df
    else:
        pd.DataFrame(logs).to_csv(logs_file, index=False)

    essential_metrics = []
    for metric in detailed_metrics:
        essential_metric = {
            "query_index": metric.get("query_index"),
            "model_display_name": metric.get("model_display_name"),
            "final_answer_extracted": metric.get("final_answer_extracted"),
            "quality_correctness": metric.get("quality_correctness"),
            "response_time_seconds": metric.get("response_time_seconds"),
            "error_occurred": metric.get("error_occurred"),
        }
        essential_metrics.append(essential_metric)

    with open(metrics_file, "w") as f:
        json.dump(essential_metrics, f, indent=2, default=str)

    performance_summary = calculate_model_performance_summary(results_df, models_config)
    experiment_summary = {
        "config": config,
        "nl_patterns": nl_patterns,
        "experiment_time_seconds": experiment_time,
        "experiment_time_minutes": experiment_time / 60,
        "total_questions_processed": len(results_df),
        "total_api_calls": len(results_df) * len(models_config),
        "performance_summary": performance_summary,
        "timestamp": datetime.now().isoformat(),
        "output_directory": str(output_dir),
        "memory_info": {
            "final_memory_usage_percent": monitor_memory(),
            "memory_threshold": config["memory_threshold"],
        },
        "files_created": {
            "results": str(results_file),
            "logs": str(logs_file),
            "metrics": str(metrics_file),
            "summary": str(config_file),
        },
        "checkpoint_info": {
            "frequency": config["checkpoint_frequency"],
            "checkpoints_dir": str(output_dir / "checkpoints"),
        },
    }

    with open(config_file, "w") as f:
        json.dump(experiment_summary, f, indent=2, default=str)

    print(f"✅ Results saved to: {output_dir}")
    print(f"📄 Main results: {results_file}")
    print(f"📊 Summary: {config_file}")
    print(f"💾 Checkpoints: {output_dir / 'checkpoints'}")

    print(f"\n📈 MODEL PERFORMANCE SUMMARY:")
    for model_name, summary in performance_summary.items():
        response_metrics = summary.get("response_metrics", {})
        quality_metrics = summary.get("quality_metrics", {})

        success_rate = response_metrics.get("success_rate", 0) * 100
        avg_correctness = quality_metrics.get("correctness", {}).get("mean", 0)

        print(f"  🤖 {model_name}:")
        print(f"     Success Rate: {success_rate:.1f}%")
        print(f"     Avg Correctness: {avg_correctness:.3f}")

    force_cleanup()


def main():
    args = parse_args()
    config = build_config(args)
    models_config = config["models_used"]

    output_dir = Path(args.output_directory)
    output_dir.mkdir(parents=True, exist_ok=True)

    print_experiment_header(config, output_dir)

    if not check_api_clients(models_config):
        print("❌ Fix API client issues before proceeding")
        return

    log_models_metadata(
        models_config, output_dir, openai_client, deepseek_client, openrouter_client
    )

    previous_df, recovery_info = check_for_previous_run(output_dir)

    if previous_df is not None:
        df = previous_df
        print(f"✅ Resuming from previous run with {len(df)} questions")
        start_question = recovery_info["questions_completed"]
    else:
        df = validate_setup(config)
        if df is None:
            return
        start_question = 0

    initial_memory = monitor_memory()
    if initial_memory > 80:
        print(f"⚠️ Warning: High initial memory usage ({initial_memory:.1f}%)")
        print("   Consider closing other applications or reducing batch size")

    if config["test_mode"]:
        df_sample = df.head(50).copy()
        print(f"🧪 TEST MODE: Using {len(df_sample)} questions")
    else:
        df_sample = df.copy()
        print(f"🏭 PRODUCTION MODE: Processing all {len(df_sample)} questions")

    nl_patterns = analyze_nl_patterns(df_sample)
    print(f"\n📊 Natural Language Question Analysis:")
    for pattern, count in nl_patterns.items():
        percentage = (count / len(df_sample)) * 100
        print(f"   {pattern.replace('_', ' ').title()}: {count} ({percentage:.1f}%)")

    estimated_time = estimate_experiment_time(
        df_sample,
        models_config,
        config["max_workers"],
        config["checkpoint_frequency"],
        config["batch_size"],
    )

    print(f"\n⚠️ Ready to start experiment")
    print(
        f"📊 Processing {len(df_sample)} questions across {len(models_config)} models"
    )
    print(f"🎯 Starting from question {start_question + 1}")
    print(
        f"🔇 Silent mode: {'ON (faster)' if config['silent_mode'] else 'OFF (shows responses)'}"
    )
    print(f"🧠 Current memory: {monitor_memory():.1f}%")

    print("Starting in 3 seconds... (Ctrl+C to cancel)")
    try:
        time.sleep(3)
    except KeyboardInterrupt:
        print("\n❌ Experiment cancelled by user")
        return

    print(f"\n🧠 Starting Natural Language reasoning experiment...")
    start_time = time.time()

    try:
        results_df, logs, detailed_metrics, _ = run_llm_reasoning(
            df_sample,
            ontology_base_path=config["verbalized_ontology_dir"],
            models=models_config,
            context_mode=config["context_mode"],
            max_workers=config["max_workers"],
            question_column=config["question_column"],
            batch_size=config["batch_size"],
            output_dir=output_dir,
            silent_mode=config["silent_mode"],
        )

        experiment_time = time.time() - start_time

        print_completion_summary(
            results_df, experiment_time, estimated_time, nl_patterns, models_config
        )

        save_final_results(
            results_df,
            logs,
            detailed_metrics,
            experiment_time,
            nl_patterns,
            config,
            output_dir,
            models_config,
        )

    except KeyboardInterrupt:
        print("\n⚠️ Experiment interrupted by user")
        experiment_time = time.time() - start_time
        print(f"⏱️ Ran for {experiment_time:.1f}s ({experiment_time / 60:.1f}min)")
        print(f"💾 Progress has been saved in checkpoints")
        print(f"🧠 Final memory usage: {monitor_memory():.1f}%")

    except Exception as e:
        print(f"\n❌ Experiment failed: {e}")
        import traceback

        traceback.print_exc()
        print(f"🧠 Memory usage at failure: {monitor_memory():.1f}%")


if __name__ == "__main__":
    main()
