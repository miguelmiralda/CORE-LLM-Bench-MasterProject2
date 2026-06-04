import pandas as pd
import json
import numpy as np
import re
import argparse
from deepeval.test_case import LLMTestCase
from deepeval.metrics import BaseMetric
from collections import defaultdict
from typing import Dict, List, Any
from pathlib import Path
from dataclasses import dataclass, field
import os
from scipy import stats

# Navigate to project root
script_dir = Path(__file__).resolve().parent
project_root = script_dir.parent.parent
os.chdir(project_root)


TAG_GROUPS = {
    "Basic_Hierarchy": ["H", "I"],
    "Property_Characteristics": ["T", "S"],
    "Class_Relations": ["Q", "J"],
    "Restrictions": ["C", "E", "L"],
    "Advanced_Properties": ["V", "Y", "A"],
    "Boolean_Logic": ["∩", "∪", "¬"],
    "Domain_Range": ["R"],
    "Property_Chains": ["N"],
    "Functional": ["F"],
    "Multi_Step": ["M"],
}

TAG_DESCRIPTIONS = {
    "D": "Direct Assertion",
    "H": "Hierarchy",
    "T": "Transitivity",
    "S": "Symmetry",
    "I": "Inverse",
    "F": "Functional Property",
    "Q": "Equivalence",
    "J": "Disjointness",
    "R": "Domain/Range",
    "N": "Property Chain",
    "C": "Cardinality",
    "E": "Existential Restriction",
    "L": "Universal Restriction",
    "V": "Reflexivity",
    "Y": "Irreflexivity",
    "A": "Asymmetry",
    "∩": "Intersection",
    "∪": "Union",
    "¬": "Complement",
    "M": "Multi-step Combination",
}


def parse_args():
    parser = argparse.ArgumentParser(
        description="Run ontology reasoning evaluation with tag group analysis."
    )
    parser.add_argument(
        "--csv-file",
        type=str,
        required=True,
        help="Path to the evaluation CSV file.",
    )
    parser.add_argument(
        "--explanations-file",
        type=str,
        required=True,
        help="Path to the explanations JSON file.",
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        required=True,
        help="Path to the output directory.",
    )
    parser.add_argument(
        "--jaccard-threshold",
        type=float,
        default=1.0,
        help="Threshold for Jaccard Accuracy success.",
    )
    parser.add_argument(
        "--calibration-threshold",
        type=float,
        default=0.7,
        help="Threshold for Confidence Calibration success.",
    )
    parser.add_argument(
        "--hallucination-threshold",
        type=float,
        default=0.7,
        help="Threshold for Ontology Hallucination Detection success.",
    )
    parser.add_argument(
        "--bootstrap-samples",
        type=int,
        default=1000,
        help="Number of bootstrap samples for confidence intervals.",
    )
    parser.add_argument(
        "--min-samples-statistics",
        type=int,
        default=30,
        help="Minimum number of samples required for statistical analysis.",
    )
    return parser.parse_args()


def load_partial_json_object(file_path: Path):
    with open(file_path, "r", encoding="utf-8") as f:
        text = f.read()

    decoder = json.JSONDecoder()
    stripped = text.lstrip()
    if not stripped.startswith("{"):
        raise ValueError("Expected top-level JSON object")

    pos = text.find("{") + 1
    result = {}

    while True:
        while pos < len(text) and text[pos] in " \r\n\t,":
            pos += 1

        if pos < len(text) and text[pos] == "}":
            return result

        try:
            key, pos = decoder.raw_decode(text, pos)

            while pos < len(text) and text[pos] in " \r\n\t":
                pos += 1

            if pos >= len(text) or text[pos] != ":":
                break
            pos += 1

            while pos < len(text) and text[pos] in " \r\n\t":
                pos += 1

            value, pos = decoder.raw_decode(text, pos)
            result[key] = value

        except json.JSONDecodeError:
            return result

    return result


@dataclass
class EvalCase:
    test_case: LLMTestCase
    metadata: Dict[str, Any] = field(default_factory=dict)
    metrics_data: Dict[str, Any] = field(default_factory=dict)


class JaccardAccuracyMetric(BaseMetric):
    def __init__(self, threshold: float = 0.0):
        self.threshold = threshold
        self.async_mode = False

    async def a_measure(self, case):
        return self.measure(case)

    def measure(self, case):
        test_case = case.test_case
        metadata = case.metadata

        expected = str(test_case.expected_output).strip()
        actual = str(test_case.actual_output).strip()
        answer_type = metadata.get("answer_type", "BIN")

        def clean_answer(text):
            text = text.lower().strip()
            text = re.sub(r"\s+", " ", text)

            if ";" in text:
                items = text.split(";")
            elif "," in text:
                items = text.split(",")
            else:
                items = [text]

            cleaned_items = []
            for item in items:
                item = item.strip()
                item = re.sub(r'[_*#@$%^&()+=\[\]{}|\\:";\'<>?/~`]', "", item)
                item = re.sub(r"\s+", " ", item).strip()
                item = re.sub(r"\s+\d{4}$", "", item)
                item = re.sub(r"\s+\d{4}\s+", " ", item)
                item = re.sub(r"^\d+$", "", item)
                item = re.sub(r"[^a-z0-9\s]", "", item)
                item = re.sub(r"\s+", " ", item).strip()
                if item:
                    cleaned_items.append(item)

            return set(cleaned_items)

        expected_set = clean_answer(expected)
        actual_set = clean_answer(actual)

        if len(expected_set) == 0 and len(actual_set) == 0:
            score = 1.0
        elif len(expected_set) == 0 or len(actual_set) == 0:
            score = 0.0
        else:
            intersection = len(expected_set.intersection(actual_set))
            union = len(expected_set.union(actual_set))
            jaccard_score = intersection / union if union > 0 else 0.0

            if answer_type == "BIN":
                score = 1.0 if jaccard_score == 1.0 else 0.0
            else:
                score = jaccard_score

        self.success = score >= self.threshold
        self.score = score
        return score

    def is_successful(self):
        return self.success

    @property
    def __name__(self):
        return "Jaccard Accuracy"


class ConfidenceCalibrationMetric(BaseMetric):
    def __init__(self, threshold: float = 1.0):
        self.threshold = threshold
        self.async_mode = False

    async def a_measure(self, case):
        return self.measure(case)

    def measure(self, case):
        metadata = case.metadata
        confidence = metadata.get("confidence_score", 0.5)
        confidence = max(0.0, min(1.0, confidence))

        jaccard_accuracy = case.metrics_data.get("Jaccard Accuracy", {}).get("score", 0)
        is_correct = 1.0 if jaccard_accuracy >= 1.0 else 0.0

        calibration_error = abs(confidence - is_correct)
        calibration_score = 1.0 - calibration_error

        self.score = calibration_score
        self.success = calibration_score >= self.threshold
        return calibration_score

    def is_successful(self):
        return self.success

    @property
    def __name__(self):
        return "Confidence Calibration"


class OntologyHallucinationMetric(BaseMetric):
    def __init__(self, threshold: float = 1.0):
        self.threshold = threshold
        self.async_mode = False

    async def a_measure(self, case):
        return self.measure(case)

    def measure(self, case):
        test_case = case.test_case
        metadata = case.metadata

        actual_output = str(test_case.actual_output).strip()
        expected_output = str(test_case.expected_output).strip()
        answer_type = metadata.get("answer_type", "BIN")

        if answer_type == "BIN":
            self.score = None
            self.success = True
            return None

        hallucination_score = self._detect_mc_hallucinations(
            actual_output, expected_output, test_case.context
        )

        self.score = hallucination_score
        self.success = hallucination_score >= self.threshold
        return hallucination_score

    def _detect_mc_hallucinations(self, actual_output, expected_output, context):
        actual_answers = self._parse_answers(actual_output)

        if not actual_answers:
            return 1.0

        expected_answers = self._parse_answers(expected_output)
        valid_entities = self._extract_valid_entities_from_context(
            context, expected_answers
        )

        hallucinated_count = 0
        for answer in actual_answers:
            answer_normalized = self._normalize_answer(answer)

            is_valid = False
            for valid_entity in valid_entities:
                if self._answers_match(
                    answer_normalized, self._normalize_answer(valid_entity)
                ):
                    is_valid = True
                    break

            if not is_valid:
                hallucinated_count += 1

        total_answers = len(actual_answers)
        hallucination_rate = (
            hallucinated_count / total_answers if total_answers > 0 else 0
        )
        return hallucination_rate

    def _parse_answers(self, answer_text):
        text = answer_text.lower().strip()

        if ";" in text:
            answers = text.split(";")
        elif "," in text:
            answers = text.split(",")
        elif "\n" in text:
            answers = text.split("\n")
        else:
            answers = [text]

        cleaned_answers = []
        for ans in answers:
            ans = ans.strip()
            ans = re.sub(r"[^\w\s]", "", ans)
            ans = re.sub(r"\s+", " ", ans).strip()
            if ans:
                cleaned_answers.append(ans)

        return cleaned_answers

    def _extract_valid_entities_from_context(self, context, expected_answers):
        valid_entities = set(expected_answers)

        if context:
            context_text = (
                " ".join(context) if isinstance(context, list) else str(context)
            )

            individual_pattern = r"\b[a-z]+(?:_[a-z]+)*_\d{4}\b"
            individuals = re.findall(individual_pattern, context_text.lower())
            valid_entities.update(individuals)

            class_pattern = r"\b[A-Z][a-zA-Z]+\b"
            classes = re.findall(class_pattern, context_text)
            valid_entities.update([c.lower() for c in classes])

            sparql_pattern = r"<[^>]+#([^>]+)>"
            sparql_entities = re.findall(sparql_pattern, context_text)
            valid_entities.update([e.lower() for e in sparql_entities])

        return valid_entities

    def _normalize_answer(self, answer):
        normalized = answer.lower().strip()
        normalized = normalized.replace("_", " ")
        normalized = re.sub(r"\s*\d{4}$", "", normalized)
        normalized = re.sub(r"\s+", " ", normalized).strip()
        return normalized

    def _answers_match(self, answer1, answer2):
        if answer1 == answer2:
            return True
        if answer1 in answer2 or answer2 in answer1:
            return True
        if answer1.replace(" ", "") == answer2.replace(" ", ""):
            return True
        return False

    def is_successful(self):
        return self.success

    @property
    def __name__(self):
        return "Ontology Hallucination Detection"


class CompleteEvaluator:
    def __init__(
        self,
        csv_file: str,
        explanations_file: str,
        output_dir: str,
        jaccard_threshold: float = 1.0,
        calibration_threshold: float = 0.7,
        hallucination_threshold: float = 0.7,
        bootstrap_samples: int = 1000,
        min_samples_statistics: int = 30,
    ):
        self.csv_file = Path(csv_file)
        self.explanations_file = Path(explanations_file)
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

        self.jaccard_threshold = jaccard_threshold
        self.calibration_threshold = calibration_threshold
        self.hallucination_threshold = hallucination_threshold
        self.bootstrap_samples = bootstrap_samples
        self.min_samples_statistics = min_samples_statistics

        csv_filename = self.csv_file.stem
        self.file_prefix = csv_filename.split("_")[0]

        self.df = pd.read_csv(self.csv_file)
        self._validate_csv_columns()

        try:
            with open(self.explanations_file, "r", encoding="utf-8") as f:
                self.explanations = json.load(f)
        except json.JSONDecodeError:
            print("⚠️ Explanations JSON is malformed; attempting partial recovery.")
            self.explanations = load_partial_json_object(self.explanations_file)
            print(f"⚠️ Recovered {len(self.explanations)} explanation entries.")

        self.models = self._detect_models()
        self.sparql_to_explanations = self._create_sparql_mapping()
        self.tag_group_distribution = self._analyze_tag_group_distribution()

        print(f"📊 Loaded {len(self.df)} questions")
        print(f"📁 Found explanations for {len(self.explanations)} inferences")
        print(
            f"🔗 Created SPARQL mappings for {len(self.sparql_to_explanations)} queries"
        )
        print(f"🤖 Detected models: {list(self.models.keys())}")
        print("📊 Tag Group Distribution:")
        for group_name, stats_dict in sorted(
            self.tag_group_distribution.items(),
            key=lambda x: x[1]["percentage_of_total"],
            reverse=True,
        ):
            print(f"   {group_name:<20}: {stats_dict['percentage_of_total']:.1f}%")

    def _validate_csv_columns(self):
        required_columns = ["SPARQL Query", "Answer Type", "Answer"]
        missing = [col for col in required_columns if col not in self.df.columns]
        if missing:
            raise ValueError(
                f"CSV is missing required columns: {missing}\n"
                f"Available columns: {list(self.df.columns)}"
            )

    def add_statistical_analysis(self, all_results):
        print("\n📊 STATISTICAL ANALYSIS")
        print("=" * 50)

        print("\n📈 Performance Confidence Intervals (95% CI):")
        model_stats = {}

        for model_name, results in all_results.items():
            jaccard_scores = [
                r.metrics_data.get("Jaccard Accuracy", {}).get("score", 0)
                for r in results["eval_results"]
            ]

            if len(jaccard_scores) > self.min_samples_statistics:
                bootstrap_means = []
                n = len(jaccard_scores)

                for _ in range(self.bootstrap_samples):
                    bootstrap_sample = np.random.choice(
                        jaccard_scores, size=n, replace=True
                    )
                    bootstrap_means.append(np.mean(bootstrap_sample))

                ci_lower = np.percentile(bootstrap_means, 2.5)
                ci_upper = np.percentile(bootstrap_means, 97.5)
                mean_score = np.mean(jaccard_scores)

                model_stats[model_name] = {
                    "mean": mean_score,
                    "ci_lower": ci_lower,
                    "ci_upper": ci_upper,
                    "margin_of_error": (ci_upper - ci_lower) / 2,
                    "n": len(jaccard_scores),
                }

                print(
                    f"   {model_name}: {mean_score:.1%} [{ci_lower:.1%}, {ci_upper:.1%}] (n={len(jaccard_scores)})"
                )

        print("\n🔄 Pairwise Comparisons (Mann-Whitney U test):")
        pairwise_comparisons = []
        model_names = list(model_stats.keys())

        if len(model_names) >= 2:
            for i in range(len(model_names)):
                for j in range(i + 1, len(model_names)):
                    model1, model2 = model_names[i], model_names[j]

                    scores1 = [
                        r.metrics_data.get("Jaccard Accuracy", {}).get("score", 0)
                        for r in all_results[model1]["eval_results"]
                    ]
                    scores2 = [
                        r.metrics_data.get("Jaccard Accuracy", {}).get("score", 0)
                        for r in all_results[model2]["eval_results"]
                    ]

                    if (
                        len(scores1) > self.min_samples_statistics
                        and len(scores2) > self.min_samples_statistics
                    ):
                        _, p_value = stats.mannwhitneyu(
                            scores1, scores2, alternative="two-sided"
                        )
                        significance_level = (
                            "***"
                            if p_value < 0.001
                            else "**"
                            if p_value < 0.01
                            else "*"
                            if p_value < 0.05
                            else "ns"
                        )

                        mean_diff = np.mean(scores1) - np.mean(scores2)

                        comparison = {
                            "models": f"{model1} vs {model2}",
                            "mean_difference": mean_diff,
                            "p_value": p_value,
                            "significance": significance_level,
                            "is_significant": p_value < 0.05,
                        }
                        pairwise_comparisons.append(comparison)

                        print(
                            f"   {model1} vs {model2}: Δ={mean_diff:+.1%}, p={p_value:.3f} {significance_level}"
                        )

        return model_stats, pairwise_comparisons

    def _detect_models(self):
        models = {}
        for col in self.df.columns:
            if col.endswith("_final_answer"):
                model_name = col.replace("_final_answer", "")
                models[model_name] = model_name
        return models

    def _normalize_sparql(self, sparql_query: str) -> str:
        if not sparql_query:
            return ""
        normalized = re.sub(r"\s+", " ", sparql_query.strip())
        normalized = normalized.rstrip(". ")
        return normalized

    def _create_sparql_mapping(self):
        sparql_mapping = defaultdict(list)

        for explanation_key, explanation_data in self.explanations.items():
            sparql_queries = explanation_data.get("sparqlQueries", [])

            for sparql_query in sparql_queries:
                normalized_query = self._normalize_sparql(sparql_query)

                explanation_info = {
                    "explanation_key": explanation_key,
                    "inferred": explanation_data.get("inferred", {}),
                    "explanations": explanation_data.get("explanations", []),
                    "explanation_count": explanation_data.get("explanationCount", 0),
                    "size": explanation_data.get("size", {}),
                    "task_ids": explanation_data.get("taskIds", []),
                }

                sparql_mapping[normalized_query].append(explanation_info)

        return dict(sparql_mapping)

    def _find_explanations_for_sparql(self, sparql_query: str):
        normalized_query = self._normalize_sparql(sparql_query)

        if normalized_query in self.sparql_to_explanations:
            return self.sparql_to_explanations[normalized_query]

        for stored_query, explanations in self.sparql_to_explanations.items():
            if self._queries_similar(normalized_query, stored_query):
                return explanations

        return []

    def _queries_similar(
        self, query1: str, query2: str, threshold: float = 0.9
    ) -> bool:
        tokens1 = set(re.findall(r"\w+", query1.lower()))
        tokens2 = set(re.findall(r"\w+", query2.lower()))

        if not tokens1 or not tokens2:
            return False

        intersection = len(tokens1.intersection(tokens2))
        union = len(tokens1.union(tokens2))
        similarity = intersection / union if union > 0 else 0
        return similarity >= threshold

    def _extract_shortest_explanation_tags(self, explanations_list: List[dict]) -> dict:
        all_shortest_tags = []
        inference_details = []

        for explanation_info in explanations_list:
            explanations = explanation_info.get("explanations", [])
            inferred = explanation_info.get("inferred", {})

            if not explanations:
                continue

            shortest_explanation = None
            shortest_tag_length = float("inf")

            for explanation in explanations:
                if len(explanation) > 2 and explanation[2].startswith("TAG:"):
                    tag = explanation[2].replace("TAG:", "")
                    tag_without_d = tag.replace("D", "")
                    tag_length = len(tag_without_d)

                    if tag_length < shortest_tag_length:
                        shortest_tag_length = tag_length
                        shortest_explanation = {
                            "tag": tag,
                            "tag_without_d": tag_without_d,
                            "tag_length": tag_length,
                            "explanation_text": explanation[0]
                            if len(explanation) > 0
                            else "",
                            "rule_text": explanation[1] if len(explanation) > 1 else "",
                            "inferred_triple": inferred,
                            "inference_key": explanation_info.get(
                                "explanation_key", ""
                            ),
                        }

            if shortest_explanation:
                all_shortest_tags.append(shortest_explanation["tag_without_d"])
                inference_details.append(shortest_explanation)

        if not all_shortest_tags:
            return {
                "all_shortest_tags": [],
                "combined_tag_string": "",
                "tag_groups": [],
                "individual_tags": [],
                "inference_details": [],
                "inference_count": 0,
            }

        all_individual_tags = []
        for tag in all_shortest_tags:
            all_individual_tags.extend(list(tag))

        combined_tag_string = "".join(all_shortest_tags)
        tag_groups = self._analyze_tag_groups(combined_tag_string)

        return {
            "all_shortest_tags": all_shortest_tags,
            "combined_tag_string": combined_tag_string,
            "tag_groups": tag_groups,
            "individual_tags": list(set(all_individual_tags)),
            "inference_details": inference_details,
            "inference_count": len(inference_details),
        }

    def _analyze_tag_groups(self, tag_string: str) -> List[str]:
        if not tag_string:
            return []

        found_groups = []
        tag_chars = list(tag_string)

        for group_name, group_tags in TAG_GROUPS.items():
            if any(tag in tag_chars for tag in group_tags):
                found_groups.append(group_name)

        return found_groups

    def _analyze_tag_group_distribution(self):
        total_queries = len(self.df)
        group_distribution = defaultdict(int)

        for _, row in self.df.iterrows():
            sparql_query = row.get("SPARQL Query", "")
            explanations_list = self._find_explanations_for_sparql(sparql_query)

            if explanations_list:
                tag_info = self._extract_shortest_explanation_tags(explanations_list)
                tag_groups = tag_info["tag_groups"]

                for group in tag_groups:
                    group_distribution[group] += 1

        tag_group_percentages = {}
        for group_name, count in group_distribution.items():
            percentage = (count / total_queries) * 100 if total_queries > 0 else 0
            tag_group_percentages[group_name] = {"percentage_of_total": percentage}

        return tag_group_percentages

    def _create_test_cases(self, model_name: str):
        test_cases = []
        final_answer_col = f"{model_name}_final_answer"
        confidence_col = f"{model_name}_confidence_score"
        time_col = f"{model_name}_response_time"
        tokens_col = f"{model_name}_token_count"

        total_rows = len(self.df)
        bin_total = len(self.df[self.df["Answer Type"] == "BIN"])
        mc_total = len(self.df[self.df["Answer Type"] == "MC"])

        bin_skipped = 0
        mc_skipped = 0
        bin_processed = 0
        mc_processed = 0

        found_explanations = 0
        missing_explanations = 0

        for idx, row in self.df.iterrows():
            answer_type = row.get("Answer Type", "BIN")

            if final_answer_col not in self.df.columns:
                raise ValueError(f"Missing required model column: {final_answer_col}")

            if (
                pd.isna(row[final_answer_col])
                or row[final_answer_col] == ""
                or str(row[final_answer_col]).startswith("[ERROR]")
                or str(row[final_answer_col]).startswith("ERROR")
            ):
                if answer_type == "BIN":
                    bin_skipped += 1
                else:
                    mc_skipped += 1
                continue

            if answer_type == "BIN":
                bin_processed += 1
            else:
                mc_processed += 1

            sparql_query = row.get("SPARQL Query", "")
            explanations_list = self._find_explanations_for_sparql(sparql_query)

            if explanations_list:
                found_explanations += 1
                tag_info = self._extract_shortest_explanation_tags(explanations_list)
            else:
                missing_explanations += 1
                tag_info = {
                    "all_shortest_tags": [],
                    "combined_tag_string": "",
                    "tag_groups": [],
                    "individual_tags": [],
                    "inference_details": [],
                    "inference_count": 0,
                }

            context_parts = [
                f"Task Type: {row.get('Task Type', '')}",
                f"Answer Type: {row.get('Answer Type', 'BIN')}",
                f"Ontology: {row.get('Root Entity', '')}",
                f"SPARQL Query: {sparql_query}",
            ]

            if tag_info["inference_count"] > 0:
                context_parts.append(
                    f"Number of Inferences: {tag_info['inference_count']}"
                )
                context_parts.append(
                    f"Shortest Tags per Inference: {', '.join(tag_info['all_shortest_tags'])}"
                )
                context_parts.append(
                    f"Combined Reasoning Groups: {', '.join(tag_info['tag_groups'])}"
                )

            metadata = {
                "task_id": row.get("Task ID", ""),
                "root_entity": row.get("Root Entity", ""),
                "answer_type": row.get("Answer Type", "BIN"),
                "task_type": row.get("Task Type", ""),
                "all_shortest_tags": tag_info["all_shortest_tags"],
                "combined_tag_string": tag_info["combined_tag_string"],
                "tag_groups": tag_info["tag_groups"],
                "individual_tags": tag_info["individual_tags"],
                "inference_count": tag_info["inference_count"],
                "inference_details": tag_info["inference_details"],
                "original_index": idx,
                "explanations_found": len(explanations_list) > 0,
                "confidence_score": row.get(confidence_col, 0.5),
            }

            if time_col in self.df.columns and not pd.isna(row.get(time_col)):
                metadata["response_time"] = row.get(time_col)
            if tokens_col in self.df.columns and not pd.isna(row.get(tokens_col)):
                metadata["token_count"] = row.get(tokens_col)

            test_case = LLMTestCase(
                input=sparql_query,
                actual_output=row[final_answer_col],
                expected_output=row.get("Answer", ""),
                context=context_parts,
            )

            test_cases.append(EvalCase(test_case=test_case, metadata=metadata))

        print(f"📊 {model_name} Test Case Creation:")
        print(
            f"   📋 Total dataset: {total_rows} questions ({bin_total} BIN, {mc_total} MC)"
        )
        print(
            f"   ❌ Skipped (errors): {bin_skipped + mc_skipped} ({bin_skipped} BIN, {mc_skipped} MC)"
        )
        print(
            f"   ✅ Processed: {bin_processed + mc_processed} ({bin_processed} BIN, {mc_processed} MC)"
        )

        total_processed = bin_processed + mc_processed
        if total_processed > 0:
            print(
                f"   📊 Final distribution: {bin_processed / total_processed * 100:.1f}% BIN, {mc_processed / total_processed * 100:.1f}% MC"
            )

        print(f"   🔍 Found explanations: {found_explanations}")
        print(f"   ❓ Missing explanations: {missing_explanations}")

        if (bin_skipped + mc_skipped) > 0:
            bin_error_rate = (bin_skipped / bin_total * 100) if bin_total > 0 else 0
            mc_error_rate = (mc_skipped / mc_total * 100) if mc_total > 0 else 0
            print(
                f"   ⚠️  Error rate by type: BIN {bin_skipped}/{bin_total} ({bin_error_rate:.1f}%), MC {mc_skipped}/{mc_total} ({mc_error_rate:.1f}%)"
            )

        return test_cases

    def evaluate_model(self, model_name: str):
        print(f"\n🔍 Evaluating {model_name}...")

        test_cases = self._create_test_cases(model_name)

        if not test_cases:
            print(f"❌ No valid test cases for {model_name}")
            return None

        metrics = [
            JaccardAccuracyMetric(threshold=self.jaccard_threshold),
            ConfidenceCalibrationMetric(threshold=self.calibration_threshold),
            OntologyHallucinationMetric(threshold=self.hallucination_threshold),
        ]

        print("⚙️ Running manual evaluation...")

        eval_results = []
        for i, case in enumerate(test_cases):
            case.metrics_data = {}

            for metric in metrics:
                try:
                    score = metric.measure(case)
                    case.metrics_data[metric.__name__] = {
                        "score": score,
                        "success": metric.is_successful(),
                    }
                except Exception as e:
                    print(
                        f"\n    Warning: Error in {metric.__name__} for case {i}: {e}"
                    )
                    case.metrics_data[metric.__name__] = {
                        "score": 0,
                        "success": False,
                    }

            eval_results.append(case)

        print(f"  ✅ Evaluated {len(eval_results)} test cases")

        summary = self._calculate_summary(eval_results)
        tag_group_analysis = self._analyze_by_tag_groups(eval_results)

        return {
            "model_name": model_name,
            "eval_results": eval_results,
            "summary": summary,
            "tag_group_analysis": tag_group_analysis,
        }

    def _analyze_by_tag_groups(self, eval_results):
        group_analysis = defaultdict(list)

        for result in eval_results:
            tag_groups = result.metadata.get("tag_groups", [])
            jaccard_score = result.metrics_data.get("Jaccard Accuracy", {}).get(
                "score", 0
            )
            calibration_score = result.metrics_data.get(
                "Confidence Calibration", {}
            ).get("score", 0)
            hallucination_score = result.metrics_data.get(
                "Ontology Hallucination Detection", {}
            ).get("score", 0)
            answer_type = result.metadata.get("answer_type", "BIN")
            inference_count = result.metadata.get("inference_count", 0)

            for group in tag_groups:
                group_analysis[group].append(
                    {
                        "jaccard": jaccard_score,
                        "calibration": calibration_score,
                        "hallucination": hallucination_score,
                        "answer_type": answer_type,
                        "inference_count": inference_count,
                        "is_correct": jaccard_score >= 1.0,
                    }
                )

        aggregated_groups = {}
        for group_name, results in group_analysis.items():
            if len(results) >= 3:
                correct_count = sum(1 for r in results if r["is_correct"])
                total_count = len(results)

                percentage_of_total = self.tag_group_distribution.get(
                    group_name, {}
                ).get("percentage_of_total", 0.0)

                valid_hallucination_scores = [
                    r["hallucination"]
                    for r in results
                    if r["hallucination"] is not None
                ]

                aggregated_groups[group_name] = {
                    "percentage_of_total": percentage_of_total,
                    "accuracy_rate": correct_count / total_count,
                    "jaccard_mean": np.mean([r["jaccard"] for r in results]),
                    "jaccard_std": np.std([r["jaccard"] for r in results]),
                    "calibration_mean": np.mean([r["calibration"] for r in results]),
                    "calibration_std": np.std([r["calibration"] for r in results]),
                    "hallucination_mean": np.mean(valid_hallucination_scores)
                    if valid_hallucination_scores
                    else 0,
                    "hallucination_std": np.std(valid_hallucination_scores)
                    if valid_hallucination_scores
                    else 0,
                    "binary_count": sum(
                        1 for r in results if r["answer_type"] == "BIN"
                    ),
                    "mc_count": sum(1 for r in results if r["answer_type"] == "MC"),
                    "avg_inference_count": np.mean(
                        [r["inference_count"] for r in results]
                    ),
                }

        return aggregated_groups

    def _calculate_summary(self, eval_results):
        total_dataset_questions = len(self.df)
        bin_dataset_questions = len(self.df[self.df["Answer Type"] == "BIN"])
        mc_dataset_questions = len(self.df[self.df["Answer Type"] == "MC"])

        dataset_counts = {
            "total": total_dataset_questions,
            "bin": bin_dataset_questions,
            "mc": mc_dataset_questions,
        }

        summary_results = {}
        summary_results["overall"] = self._calculate_summary_for_subset(
            eval_results, dataset_counts
        )

        summary_results["dataset_composition"] = {
            "total_questions": total_dataset_questions,
            "bin_questions": bin_dataset_questions,
            "mc_questions": mc_dataset_questions,
            "bin_percentage": (bin_dataset_questions / total_dataset_questions) * 100
            if total_dataset_questions > 0
            else 0,
            "mc_percentage": (mc_dataset_questions / total_dataset_questions) * 100
            if total_dataset_questions > 0
            else 0,
        }

        bin_cases = [r for r in eval_results if r.metadata.get("answer_type") == "BIN"]
        mc_cases = [r for r in eval_results if r.metadata.get("answer_type") == "MC"]

        summary_results["binary"] = self._calculate_summary_for_subset(
            bin_cases, {"total": bin_dataset_questions}
        )
        summary_results["mc"] = self._calculate_summary_for_subset(
            mc_cases, {"total": mc_dataset_questions}
        )

        return summary_results

    def _calculate_summary_for_subset(
        self, test_cases: List[EvalCase], dataset_counts: dict = None
    ):
        total_cases = len(test_cases)
        if total_cases == 0:
            return {
                "total_test_cases": 0,
                "jaccard_accuracy": {
                    "mean": 0,
                    "std": 0,
                    "perfect_answers": 0,
                    "partial_answers": 0,
                    "wrong_answers": 0,
                    "perfect_rate": 0,
                    "partial_rate": 0,
                    "wrong_rate": 0,
                },
                "confidence_calibration": {"mean": 0, "std": 0, "well_calibrated": 0},
                "hallucination_detection": {
                    "mean": 0,
                    "std": 0,
                    "clean_responses": 0,
                    "hallucination_rate": 0,
                    "severe_hallucinations": 0,
                },
                "response_time_ms": {"mean": 0, "std": 0},
                "token_count": {"mean": 0, "std": 0},
                "dataset_percentage": 0.0,
            }

        jaccard_scores = []
        answer_categories = {"perfect": 0, "partial": 0, "wrong": 0}
        calibration_scores = []
        hallucination_scores = []
        response_times = []
        token_counts = []

        for result in test_cases:
            metrics_data = result.metrics_data
            answer_type = result.metadata.get("answer_type", "BIN")

            if "Jaccard Accuracy" in metrics_data:
                score = metrics_data["Jaccard Accuracy"]["score"]
                jaccard_scores.append(score)

                if answer_type == "BIN":
                    if score == 1.0:
                        answer_categories["perfect"] += 1
                    else:
                        answer_categories["wrong"] += 1
                else:
                    if score == 1.0:
                        answer_categories["perfect"] += 1
                    elif score > 0.0:
                        answer_categories["partial"] += 1
                    else:
                        answer_categories["wrong"] += 1

            if "Confidence Calibration" in metrics_data:
                calibration_scores.append(
                    metrics_data["Confidence Calibration"]["score"]
                )

            if "Ontology Hallucination Detection" in metrics_data:
                score = metrics_data["Ontology Hallucination Detection"]["score"]
                if score is not None:
                    hallucination_scores.append(score)

            if "response_time" in result.metadata and not pd.isna(
                result.metadata["response_time"]
            ):
                response_times.append(result.metadata["response_time"])
            if "token_count" in result.metadata and not pd.isna(
                result.metadata["token_count"]
            ):
                token_counts.append(result.metadata["token_count"])

        dataset_percentage = 0.0
        if dataset_counts:
            total_dataset = dataset_counts.get("total", 0)
            if total_dataset > 0:
                dataset_percentage = (total_cases / total_dataset) * 100

        return {
            "total_test_cases": total_cases,
            "dataset_percentage": dataset_percentage,
            "jaccard_accuracy": {
                "mean": np.mean(jaccard_scores) if jaccard_scores else 0,
                "std": np.std(jaccard_scores) if jaccard_scores else 0,
                "perfect_answers": answer_categories["perfect"],
                "partial_answers": answer_categories["partial"],
                "wrong_answers": answer_categories["wrong"],
                "perfect_rate": answer_categories["perfect"] / total_cases
                if total_cases > 0
                else 0,
                "partial_rate": answer_categories["partial"] / total_cases
                if total_cases > 0
                else 0,
                "wrong_rate": answer_categories["wrong"] / total_cases
                if total_cases > 0
                else 0,
            },
            "confidence_calibration": {
                "mean": np.mean(calibration_scores) if calibration_scores else 0,
                "std": np.std(calibration_scores) if calibration_scores else 0,
                "well_calibrated": sum(
                    1 for s in calibration_scores if s >= self.calibration_threshold
                )
                if calibration_scores
                else 0,
            },
            "hallucination_detection": {
                "mean": np.mean(hallucination_scores) if hallucination_scores else 0,
                "std": np.std(hallucination_scores) if hallucination_scores else 0,
                "clean_responses": sum(
                    1 for s in hallucination_scores if s >= self.hallucination_threshold
                )
                if hallucination_scores
                else 0,
                "hallucination_rate": sum(
                    1 for s in hallucination_scores if s < self.hallucination_threshold
                )
                / len(hallucination_scores)
                if hallucination_scores
                else 0,
                "severe_hallucinations": sum(1 for s in hallucination_scores if s < 0.3)
                if hallucination_scores
                else 0,
            },
            "response_time_ms": {
                "mean": np.mean(response_times) if response_times else 0,
                "std": np.std(response_times) if response_times else 0,
            },
            "token_count": {
                "mean": np.mean(token_counts) if token_counts else 0,
                "std": np.std(token_counts) if token_counts else 0,
            },
        }

    def _create_summary_json(self, all_results: Dict):
        print("\n💾 Saving key findings summary to JSON...")

        if not all_results:
            print("❌ No results to save - all models failed evaluation")
            return

        statistical_analysis = {}
        if len(all_results) > 1:
            model_stats, pairwise_comparisons = self.add_statistical_analysis(
                all_results
            )

            statistical_analysis = {
                "confidence_intervals": {
                    model: {
                        "mean_accuracy": f"{stats_dict['mean']:.1%}",
                        "confidence_interval_lower": f"{stats_dict['ci_lower']:.1%}",
                        "confidence_interval_upper": f"{stats_dict['ci_upper']:.1%}",
                        "margin_of_error": f"{stats_dict['margin_of_error']:.1%}",
                        "sample_size": stats_dict["n"],
                    }
                    for model, stats_dict in model_stats.items()
                },
                "pairwise_comparisons": [
                    {
                        "comparison": comp["models"],
                        "mean_difference": f"{comp['mean_difference']:+.1%}",
                        "p_value": f"{comp['p_value']:.6f}",
                        "significance_level": comp["significance"],
                        "statistically_significant": bool(comp["is_significant"]),
                    }
                    for comp in pairwise_comparisons
                ],
            }

        first_model = next(iter(all_results.values()))
        dataset_composition = first_model["summary"]["dataset_composition"]

        summary_data = {
            "dataset_composition": {
                "total_questions": dataset_composition["total_questions"],
                "binary_questions": f"{dataset_composition['bin_questions']} ({dataset_composition['bin_percentage']:.1f}%)",
                "mc_questions": f"{dataset_composition['mc_questions']} ({dataset_composition['mc_percentage']:.1f}%)",
            },
            "configuration": {
                "csv_file": str(self.csv_file),
                "explanations_file": str(self.explanations_file),
                "output_dir": str(self.output_dir),
                "jaccard_threshold": self.jaccard_threshold,
                "calibration_threshold": self.calibration_threshold,
                "hallucination_threshold": self.hallucination_threshold,
                "bootstrap_samples": self.bootstrap_samples,
                "min_samples_statistics": self.min_samples_statistics,
            },
            "statistical_analysis": statistical_analysis,
            "key_findings_summary": {},
        }

        for model_name, model_results in all_results.items():
            tag_group_data = {}
            tag_groups = model_results["tag_group_analysis"]
            for group, stats_dict in tag_groups.items():
                tag_group_data[group] = {
                    "accuracy": f"{stats_dict['jaccard_mean']:.1%}",
                    "percentage_of_total": f"{stats_dict['percentage_of_total']:.1f}%",
                }

            summary_data["key_findings_summary"][model_name] = {
                "overall_metrics": {
                    "average_accuracy": f"{model_results['summary']['overall']['jaccard_accuracy']['mean']:.1%}",
                    "perfect_answers": f"{model_results['summary']['overall']['jaccard_accuracy']['perfect_rate']:.1%}",
                    "partial_answers": f"{model_results['summary']['overall']['jaccard_accuracy']['partial_rate']:.1%}",
                    "wrong_answers": f"{model_results['summary']['overall']['jaccard_accuracy']['wrong_rate']:.1%}",
                    "confidence_calibration": f"{model_results['summary']['overall']['confidence_calibration']['mean']:.1%}",
                    "hallucination_score": f"{model_results['summary']['overall']['hallucination_detection']['mean']:.1%}",
                    "average_response_time_ms": f"{model_results['summary']['overall']['response_time_ms']['mean']:.2f}",
                    "average_token_count": f"{model_results['summary']['overall']['token_count']['mean']:.2f}",
                },
                "tag_group_analysis": tag_group_data,
                "performance_by_answer_type": {
                    "binary": {
                        "dataset_questions": f"{dataset_composition['bin_questions']} ({dataset_composition['bin_percentage']:.1f}%)",
                        "successfully_evaluated": f"{model_results['summary']['binary']['total_test_cases']} ({model_results['summary']['binary']['dataset_percentage']:.1f}%)",
                        "average_accuracy": f"{model_results['summary']['binary']['jaccard_accuracy']['mean']:.1%}",
                        "average_response_time_ms": f"{model_results['summary']['binary']['response_time_ms']['mean']:.2f}",
                        "average_token_count": f"{model_results['summary']['binary']['token_count']['mean']:.2f}",
                    },
                    "mc": {
                        "dataset_questions": f"{dataset_composition['mc_questions']} ({dataset_composition['mc_percentage']:.1f}%)",
                        "successfully_evaluated": f"{model_results['summary']['mc']['total_test_cases']} ({model_results['summary']['mc']['dataset_percentage']:.1f}%)",
                        "average_accuracy": f"{model_results['summary']['mc']['jaccard_accuracy']['mean']:.1%}",
                        "average_response_time_ms": f"{model_results['summary']['mc']['response_time_ms']['mean']:.2f}",
                        "average_token_count": f"{model_results['summary']['mc']['token_count']['mean']:.2f}",
                    },
                },
            }

        output_path = self.output_dir / f"{self.file_prefix}_key_findings_summary.json"
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(summary_data, f, indent=2)

        print(f"✅ Summary JSON saved to: {output_path}")

    def evaluate_all_models(self):
        all_results = {}

        for model_name in self.models.keys():
            try:
                results = self.evaluate_model(model_name)
                if results:
                    all_results[model_name] = results
            except Exception as e:
                print(f"❌ Error evaluating {model_name}: {e}")
                continue

        if len(all_results) > 1:
            self.add_statistical_analysis(all_results)

        self._create_summary_json(all_results)
        return all_results


def main():
    args = parse_args()

    print("🚀 Starting Ontology Reasoning Evaluation with Tag Group Analysis")
    print("=" * 70)
    print("📋 Focus: Analyzing performance by SHORTEST explanation tags")
    print("🎯 Groupings:")
    for group_name, tags in TAG_GROUPS.items():
        tag_descriptions = [TAG_DESCRIPTIONS.get(tag, tag) for tag in tags]
        print(f"   {group_name:<20}: {tags} ({', '.join(tag_descriptions)})")
    print("=" * 70)

    evaluator = CompleteEvaluator(
        csv_file=args.csv_file,
        explanations_file=args.explanations_file,
        output_dir=args.output_dir,
        jaccard_threshold=args.jaccard_threshold,
        calibration_threshold=args.calibration_threshold,
        hallucination_threshold=args.hallucination_threshold,
        bootstrap_samples=args.bootstrap_samples,
        min_samples_statistics=args.min_samples_statistics,
    )

    results = evaluator.evaluate_all_models()

    print("\n✅ Evaluation completed!")
    print(f"📁 Results saved to: {evaluator.output_dir}")

    print("\n🎯 KEY FINDINGS SUMMARY:")
    print("=" * 50)

    for model_name, model_results in results.items():
        print(f"\n🤖 {model_name.upper()}:")
        summary_overall = model_results["summary"]["overall"]
        jaccard_overall = summary_overall["jaccard_accuracy"]

        print("   Overall:")
        print(f"     Average Accuracy: {jaccard_overall['mean']:.1%}")
        print(
            f"     Perfect Answers: {jaccard_overall['perfect_answers']}/{summary_overall['total_test_cases']} ({jaccard_overall['perfect_rate']:.1%})"
        )
        print(
            f"     Confidence Calibration: {summary_overall['confidence_calibration']['mean']:.1%}"
        )
        print(
            f"     Hallucination Score: {summary_overall['hallucination_detection']['mean']:.1%}"
        )

        tag_groups = model_results["tag_group_analysis"]
        sorted_groups = sorted(
            tag_groups.items(), key=lambda x: x[1]["jaccard_mean"], reverse=True
        )

        if sorted_groups:
            print("\n   💪 Best performing groups:")
            for group, stats_dict in sorted_groups[: min(3, len(sorted_groups))]:
                print(
                    f"      {group}: {stats_dict['jaccard_mean']:.1%} accuracy ({stats_dict['percentage_of_total']:.1f}% of queries)"
                )


if __name__ == "__main__":
    main()
