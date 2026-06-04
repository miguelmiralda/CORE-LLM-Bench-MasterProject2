# LLM Reasoning Pipeline

This module provides a unified, modular pipeline for:
- LLM-based question answering and reasoning over ontologies
- SPARQL-to-NL question generation
- Automated evaluation/accuracy scoring of LLM responses
- Stratified sampling of SPARQL queries
- Ontology verbalization

**Supports both CSV and pandas DataFrame input/output.**

---

## 🚦 Pipeline Overview

```mermaid
graph TD
    A[SPARQL Query Generation<br/>(output/SPARQL_questions.csv)] --> B[Stratified Sampling<br/>(stratified_sampling.py)]
    B -->|output/SPARQL_questions_sampling.csv| C[SPARQL-to-NL<br/>(sparql_to_nl.py)]
    B -->|output/SPARQL_questions_sampling.csv| D[API Calls<br/>(api_calls.py)<br/>Test 2: SPARQL+Original Ontology]
    C -->|NL_questions.csv| E[API Calls<br/>(api_calls.py)<br/>Test 1: NL+Verbalized Ontology]
    F[Ontology Verbalization<br/>(verbalize_ontologies.py)] --> E
    F --> D
    E --> G[Evaluation<br/>(evaluation.py)]
    D --> G
```

---

## 📝 **Step-by-Step Pipeline**

### 1. **SPARQL Query Generation**
- **Input:** Your full set of SPARQL queries and metadata (`output/SPARQL_questions.csv`)
- **Output:** `output/SPARQL_questions.csv`
- *(This is your own generation process, not included as a script here)*

### 2. **Stratified Sampling**
- **Script:** `scripts/llm_pipeline/stratified_sampling.py`
- **Input:** `output/SPARQL_questions.csv`
- **Output:** `output/SPARQL_questions_sampling.csv`
- **How to run:** Right-click → Run in IDE (or run main)

### 3. **SPARQL-to-NL**
- **Script:** `scripts/llm_pipeline/sparql_to_nl.py`
- **Input:** `output/SPARQL_questions_sampling.csv`
- **Output:** `output/llm_reasoning_results/NL_questions.csv` (or similar, set in code)
- **How to run:** Right-click → Run in IDE (or run main)

### 4. **Ontology Verbalization**
- **Script:** `scripts/llm_pipeline/verbalize_ontologies.py`
- **Input:** All TTLs in `src/main/resources/ontologies/family_1hop_tbox/`
- **Output:** JSONs in `output/llm_reasoning_results/verbalized_ontologies/` (one per ontology)
- **How to run:** Right-click → Run in IDE (or run main)

### 5. **API Calls (LLM Reasoning)**
- **Script:** `scripts/llm_pipeline/api_calls.py`
- **Test 1:** NL questions + verbalized ontologies
- **Test 2:** SPARQL queries + original TTLs
- **Input:**
    - For Test 1: `output/llm_reasoning_results/NL_questions.csv` + `output/llm_reasoning_results/verbalized_ontologies/`
    - For Test 2: `output/SPARQL_questions_sampling.csv` + original TTLs
- **Output:** Results CSVs (e.g., `output/llm_reasoning_results/llm_reasoning_results.csv`)
- **How to run:** Import and call from a notebook or script, or add a main function for batch runs

### 6. **Evaluation**
- **Script:** `scripts/llm_pipeline/evaluation.py`
- **Input:** Results CSVs from API calls
- **Output:** Accuracy statistics, labeled CSVs, and images in `output/llm_reasoning_results/`
- **How to run:** Import and call from a notebook or script

---

## 📂 **File Flow Summary**

| Step                | Input File(s)                              | Output File(s)                  |
|---------------------|--------------------------------------------|---------------------------------|
| SPARQL Generation   | -                                          | output/SPARQL_questions.csv     |
| Stratified Sampling | output/SPARQL_questions.csv                | output/SPARQL_questions_sampling.csv   |
| SPARQL-to-NL        | output/SPARQL_questions_sampling.csv        | output/llm_reasoning_results/NL_questions.csv |
| Verbalization       | TTLs in family_1hop_tbox/                  | output/llm_reasoning_results/verbalized_ontologies/*.json    |
| API Calls           | output/llm_reasoning_results/NL_questions.csv, output/SPARQL_questions_sampling.csv, TTLs, output/llm_reasoning_results/verbalized_ontologies/ | output/llm_reasoning_results/*.csv                   |
| Evaluation          | output/llm_reasoning_results/*.csv          | Accuracy stats, labeled CSVs, images in output/llm_reasoning_results/    |

---

## 🛠️ **How to Run Each Step**

- **All scripts can be run from the IDE.**
- Modify input/output file paths at the top of each script as needed.
- All dependencies are in `requirements.txt`.
- Set your API keys in a `.env` file for LLM calls.

---

## 🧩 **Module Structure**

- `stratified_sampling.py` — Stratified sampling of SPARQL queries
- `sparql_to_nl.py` — SPARQL-to-NL question generation (OpenAI only)
- `verbalize_ontologies.py` — Ontology verbalization to JSON
- `api_calls.py` — LLM reasoning (multiple models, both NL+verbalized and SPARQL+original)
- `evaluation.py` — Accuracy scoring and evaluation
- `requirements.txt` — All dependencies
- `README.md` — This documentation

---

## 🧪 **Example Usage**

```python
# Example: Run stratified sampling
from scripts.llm_pipeline import stratified_sampling
stratified_sampling.stratified_sample('output/SPARQL_questions.csv', 'output/SPARQL_questions_sampling.csv')

# Example: Run SPARQL-to-NL
from scripts.llm_pipeline import sparql_to_nl
sparql_to_nl.sparql_to_nl(pd.read_csv('output/SPARQL_questions_sampling.csv'))

# Example: Run API calls (see api_calls.py for details)
from scripts.llm_pipeline import api_calls
# ...

# Example: Run evaluation
from scripts.llm_pipeline import evaluation
evaluation.evaluate_model_responses(...)
```

---

## 📦 **Dependencies**
Install all dependencies:
```bash
pip install -r requirements.txt
```

---

## 📄 **License**
MIT 