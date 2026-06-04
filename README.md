# CORE-LLM-Bench: A Benchmark Dataset for Evaluating Complex Ontological REasoning Capabilities of Large Language Models

[![Data](https://img.shields.io/badge/Data-Google%20Drive-green)](https://drive.google.com/drive/u/0/folders/182veDWX2hfMtyOrFztZkctzJNp7U3FgF)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

CORE-LLM-Bench is a benchmark framework for evaluating large language models on verifiable, multi-step, ontology-based reasoning tasks. It supports benchmark generation from OWL ontologies, subgraph extraction, query generation, ontology verbalization, and evaluation under multiple LLM settings.

## Overview

The pipeline consists of five main stages:

1. Generate ontology subgraphs
2. Build the core benchmark with explanations and complexity tags
3. Prepare sampled and abstracted data
4. Convert ontologies and queries into natural language
5. Evaluate LLMs and summarize benchmark results

## Prerequisites

- **Java**: JDK 17 or higher
- **Maven**: 3.6 or higher
- **Python**: 3.8 or higher
- **Memory**: Minimum 8 GB RAM (16 GB recommended for large ontologies)
- **API keys**: OpenAI, DeepSeek, or other supported LLM providers

## Installation

### 1. Clone the repository

```bash
git clone https://github.com/jloe2911/CORE-LLM-Bench.git
cd CORE-LLM-Bench
```

### 2. Build the Java components

```bash
mvn clean install
```

### 3. Set up the Python environment

```bash
# 1. Create a Python virtual environment
python -m venv .venv

# 2. Activate the environment
# macOS/Linux
source .venv/bin/activate

# Windows (PowerShell)
.venv\Scripts\Activate.ps1

# Windows (cmd)
.venv\Scripts\activate.bat

# 3. Install dependencies
pip install -r scripts/llm_pipeline/requirements.txt
```

### 4. Configure API keys (needed for running the natural-language generation and LLM evaluation steps)

Create or edit a `.env` file with the keys you need:

```bash
OPENAI_API_KEY=your_openai_key
DEEPSEEK_API_KEY=your_deepseek_key
OPENROUTER_API_KEY=your_openrouter_key
```

### 5. Create data directories

```bash
mkdir -p data/resources data/output
```

## Dataset Generation

### Step 1: Subgraph extraction

Extract 1-hop and 2-hop subgraphs from a large ontology while preserving the complete terminological knowledge (TBox).

```bash
mvn compile
mvn exec:java "-Dexec.mainClass=SmallOntologyExtractor" "-Dexec.args=data/input/toy_example.owl data/resources/toy_example_1hop/ data/resources/toy_example_2hop/"
```

**Output:** Individual-centric subgraph ontology files that preserve the complete logical structure.

**Arguments:**

- **Input:** Path to the input OWL ontology file
- **Output (1-hop):** Path to the directory for 1-hop subgraphs
- **Output (2-hop):** Path to the directory for 2-hop subgraphs

### Step 2: Core benchmark generation

Generate the benchmark, including explanations and complexity analysis.

```bash
# 1-hop subgraphs
java -jar target/llm-orbench-1.0-SNAPSHOT.jar data/resources/toy_example_1hop/ data/output/toy_example/1hop

# 2-hop subgraphs
java -jar target/llm-orbench-1.0-SNAPSHOT.jar data/resources/toy_example_2hop/ data/output/toy_example/2hop
```

**Processing steps:**

1. **Symbolic reasoning:** Apply the Pellet reasoner to derive valid inferences
2. **Explanation generation:** Create formal proofs for each inference
3. **Complexity analysis:** Apply a 20-tag system to characterize reasoning steps
4. **Query generation:** Create SPARQL `ASK` (binary) and `SELECT` (open-ended) questions
5. **Dataset output:** Generate structured CSV and JSON files  

**Arguments:**

- **Input:** Path to a directory containing 1-hop or 2-hop subgraphs
- **Output:** Path to the directory where output files will be written

**Generated files:**

- `SPARQL_questions.csv`: Complete query dataset with metadata
- `Explanations.json`: Detailed reasoning explanations with complexity tags

## LLM Evaluation

### Step 1: Data Preparation

#### Stratified Sampling

Perform stratified sampling across complexity and size dimensions.

```bash
# 1-hop subgraphs
python scripts/llm_pipeline/stratified_sampling.py --input_file data/output/toy_example/1hop/SPARQL_questions.csv --output_file data/output/toy_example/1hop/SPARQL_questions_sampling.csv

# 2-hop subgraphs
python scripts/llm_pipeline/stratified_sampling.py --input_file data/output/toy_example/2hop/SPARQL_questions.csv --output_file data/output/toy_example/2hop/SPARQL_questions_sampling.csv
```

**Arguments:**

- **Input:** Path to the input CSV file containing 1-hop or 2-hop SPARQL questions
- **Output:** Path to the output CSV file containing the sampled questions

#### Ontology Abstraction

Create abstracted versions that remove semantic content while preserving logical structure.

```bash
# 1-hop subgraphs
python scripts/ontology_tools/abstraction/usage.py --input-directory data/resources/toy_example_1hop --output-directory data/output/abstracted_ontologies/toy_example_1hop/

# 2-hop subgraphs
python scripts/ontology_tools/abstraction/usage.py --input-directory data/resources/toy_example_2hop --output-directory data/output/abstracted_ontologies/toy_example_2hop/
```

**Arguments:**

- **Input:** Path to a directory containing 1-hop or 2-hop subgraphs
- **Output:** Path to a directory containing the abstracted ontologies

### Step 2: Verbalization

#### Ontology Verbalization

```bash
# 1-hop subgraphs
python scripts/llm_pipeline/verbalize_ontologies.py --input-dir data/resources/toy_example_1hop/ --output-dir data/output/verbalized_ontologies/toy_example_1hop/ --file-pattern "*.ttl"

# 2-hop subgraphs
python scripts/llm_pipeline/verbalize_ontologies.py --input-dir data/resources/toy_example_2hop/ --output-dir data/output/verbalized_ontologies/toy_example_2hop/ --file-pattern "*.ttl"
```

#### Abstract Ontology Verbalization

```bash
# 1-hop subgraphs
python scripts/llm_pipeline/verbalize_ontologies.py --input-dir data/output/abstracted_ontologies/toy_example_1hop/abstracted_ontologies/ --output-dir data/output/verbalized_ontologies/toy_example_1hop/abstracted/ --file-pattern "*.ttl"

# 2-hop subgraphs
python scripts/llm_pipeline/verbalize_ontologies.py --input-dir data/output/abstracted_ontologies/toy_example_2hop/abstracted_ontologies/ --output-dir data/output/verbalized_ontologies/toy_example_2hop/abstracted/ --file-pattern "*.ttl"
```

#### Convert SPARQL Questions to Abstracted Form

Convert SPARQL queries to abstracted form.

```bash
# 1-hop subgraphs 
python scripts/llm_pipeline/verbalize_abstract.py --input-file data/output/toy_example/1hop/SPARQL_questions_sampling.csv --mapping-file data/output/abstracted_ontologies/toy_example_1hop/abstraction_mappings.txt --output-file data/output/toy_example/1hop/SPARQL_questions_sampling_abs_temp.csv 

# 2-hop subgraphs 
python scripts/llm_pipeline/verbalize_abstract.py --input-file data/output/toy_example/2hop/SPARQL_questions_sampling.csv --mapping-file data/output/abstracted_ontologies/toy_example_2hop/abstraction_mappings.txt --output-file data/output/toy_example/2hop/SPARQL_questions_sampling_abs_temp.csv 
```

#### SPARQL-to-Natural-Language Conversion

Convert formal SPARQL queries to human-readable questions.

```bash
# 1-hop subgraphs (convert NL questions)
python scripts/llm_pipeline/sparql_to_nl.py --input-csv data/output/toy_example/1hop/SPARQL_questions_sampling.csv --output-directory data/output/toy_example/1hop/ --output-file SPARQL_questions_sampling_nl.csv --model gpt-4.1-mini

# 2-hop subgraphs (convert NL questions)
python scripts/llm_pipeline/sparql_to_nl.py --input-csv data/output/toy_example/2hop/SPARQL_questions_sampling.csv --output-directory data/output/toy_example/2hop/ --output-file SPARQL_questions_sampling_nl.csv --model gpt-4.1-mini

# 1-hop subgraphs (convert abstracted questions)
python scripts/llm_pipeline/sparql_to_nl.py --input-csv data/output/toy_example/1hop/SPARQL_questions_sampling_abs_temp.csv --output-directory data/output/toy_example/1hop/ --output-file SPARQL_questions_sampling_abs.csv --model gpt-4.1-mini

# 2-hop subgraphs (convert abstracted questions)
python scripts/llm_pipeline/sparql_to_nl.py --input-csv data/output/toy_example/2hop/SPARQL_questions_sampling_abs_temp.csv --output-directory data/output/toy_example/2hop/ --output-file SPARQL_questions_sampling_abs.csv --model gpt-4.1-mini
```

### Step 3: LLM Evaluation

#### Setting 1: Natural-language Questions + Verbalized Ontologies as Context

```bash
# 1-hop subgraphs
python scripts/llm_pipeline/run_nl_verbalized.py --questions-csv data/output/toy_example/1hop/SPARQL_questions_sampling_nl.csv --verbalized-ontology-dir data/output/verbalized_ontologies/toy_example_1hop/ --output-directory data/output/llm_results/toy_example_1hop/ --models openai:gpt-4.1-mini

# 2-hop subgraphs
python scripts/llm_pipeline/run_nl_verbalized.py --questions-csv data/output/toy_example/2hop/SPARQL_questions_sampling_nl.csv --verbalized-ontology-dir data/output/verbalized_ontologies/toy_example_2hop/ --output-directory data/output/llm_results/toy_example_2hop/ --models openai:gpt-4.1-mini
```

#### Setting 2: Setting 2: Abstracted Natural-language Questions + Verbalized Abstracted Ontologies as Context

```bash
# 1-hop subgraphs
python scripts/llm_pipeline/run_nl_verbalized.py --questions-csv data/output/toy_example/1hop/SPARQL_questions_sampling_abs.csv --verbalized-ontology-dir data/output/abstracted_ontologies/toy_example_1hop/ --output-directory data/output/llm_results/toy_example_1hop/ --abstracted --models openai:gpt-4.1-mini

# 2-hop subgraphs
python scripts/llm_pipeline/run_nl_verbalized.py --questions-csv data/output/toy_example/2hop/SPARQL_questions_sampling_abs.csv --verbalized-ontology-dir data/output/abstracted_ontologies/toy_example_2hop/ --output-directory data/output/llm_results/toy_example_2hop/ --abstracted --models openai:gpt-4.1-mini
```

#### Setting 3: Formal SPARQL Queries + Turtle Ontologies as Context

```bash
# 1-hop subgraphs
python scripts/llm_pipeline/run_sparql_ttl.py --questions-csv data/output/toy_example/1hop/SPARQL_questions_sampling.csv --ttl-ontology-dir data/resources/toy_example_1hop/ --output-directory data/output/llm_results/toy_example_1hop/ --models openai:gpt-4.1-mini

# 2-hop subgraphs
python scripts/llm_pipeline/run_sparql_ttl.py --questions-csv data/output/toy_example/2hop/SPARQL_questions_sampling.csv --ttl-ontology-dir data/resources/toy_example_2hop/ --output-directory data/output/llm_results/toy_example_2hop/ --models openai:gpt-4.1-mini
```

### Step 4: Summarize Key Findings

```bash
# 1-hop subgraphs (Setting 1)
python scripts/llm_pipeline/complete_evaluation.py --csv-file data/output/llm_results/toy_example_1hop/nl_verbalized_results_FINAL.csv --explanations-file data/output/toy_example/1hop/Explanations.json --output-dir data/results/toy_example_1hop/ 

# 2-hop subgraphs (Setting 1)
python scripts/llm_pipeline/complete_evaluation.py --csv-file data/output/llm_results/toy_example_2hop/nl_verbalized_results_FINAL.csv --explanations-file data/output/toy_example/2hop/Explanations.json --output-dir data/results/toy_example_2hop/ 

# 1-hop subgraphs (Setting 2)
python scripts/llm_pipeline/complete_evaluation.py --csv-file data/output/llm_results/toy_example_1hop/abs_nl_verbalized_results_FINAL.csv --explanations-file data/output/toy_example/1hop/Explanations.json --output-dir data/results/toy_example_1hop/ 

# 2-hop subgraphs (Setting 2)
python scripts/llm_pipeline/complete_evaluation.py --csv-file data/output/llm_results/toy_example_2hop/abs_nl_verbalized_results_FINAL.csv --explanations-file data/output/toy_example/2hop/Explanations.json --output-dir data/results/toy_example_2hop/ 

# 1-hop subgraphs (Setting 3)
python scripts/llm_pipeline/complete_evaluation.py --csv-file data/output/llm_results/toy_example_1hop/sparql_ttl_results_FINAL.csv --explanations-file data/output/toy_example/1hop/Explanations.json --output-dir data/results/toy_example_1hop/ 

# 2-hop subgraphs (Setting 3)
python scripts/llm_pipeline/complete_evaluation.py --csv-file data/output/llm_results/toy_example_2hop/sparql_ttl_results_FINAL.csv --explanations-file data/output/toy_example/2hop/Explanations.json --output-dir data/results/toy_example_2hop/ 
```

### Step 5: Create Final Benchmark Datasets

```bash
# 1-hop subgraphs
python final_benchmark/create_final_bench.py --dataset toy_example --hop 1hop

# 2-hop subgraphs
python final_benchmark/create_final_bench.py --dataset toy_example --hop 2hop
```

## Output Files

Typical outputs generated throughout the pipeline include:

- `SPARQL_questions.csv`: Complete query dataset with metadata
- `SPARQL_questions_sampling.csv`: Stratified sample of benchmark questions
- `SPARQL_questions_sampling_nl.csv`: Natural-language versions of sampled questions
- `SPARQL_questions_sampling_abs.csv`: Abstracted natural-language questions
- `Explanations.json`: Reasoning explanations with complexity tags
- `*_FINAL.csv`: Final LLM evaluation outputs
- `data/results/...`: Aggregated performance summaries and findings

## Notes

- Use the `toy_example` paths as a template for running the pipeline on your own ontology.
- Large ontologies may require more memory and longer processing times.
- Make sure all required API keys are available before running the natural-language generation and LLM evaluation steps.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
