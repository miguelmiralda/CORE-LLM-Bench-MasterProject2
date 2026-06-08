#!/usr/bin/env python3
"""
Aggregate OWL2Bench results to create accuracy reports for bootstrap analysis.
Processes abstract, NL, and TTL verbalization strategies across all LLMs.
"""

import pandas as pd
import json
from pathlib import Path

def extract_final_answer(response_str):
    """Extract final answer from LLM response text."""
    if pd.isna(response_str):
        return 'UNKNOWN'
    response_str = str(response_str).strip().upper()
    
    # Try to find ANSWER: pattern first
    if 'ANSWER:' in response_str:
        lines = response_str.split('\n')
        for line in lines:
            if 'ANSWER:' in line:
                answer = line.split('ANSWER:')[1].strip().upper()
                if 'TRUE' in answer:
                    return 'TRUE'
                elif 'FALSE' in answer:
                    return 'FALSE'
    
    # Fallback: check for TRUE/FALSE anywhere
    if 'TRUE' in response_str:
        return 'TRUE'
    elif 'FALSE' in response_str:
        return 'FALSE'
    
    return 'UNKNOWN'

def process_results_file(file_path, strategy_name):
    """Process a single results CSV file and return accuracy metrics per LLM."""
    print(f"  Processing {strategy_name}...")
    
    df = pd.read_csv(file_path, low_memory=False)
    
    # Expected answer column
    answer_col = 'Answer'
    if answer_col not in df.columns:
        print(f"    ⚠️  'Answer' column not found")
        return {}
    
    # Find all LLM response columns (pattern: {model}_response)
    response_cols = [col for col in df.columns if col.endswith('_response')]
    
    metrics_by_llm = {}
    
    for response_col in response_cols:
        # Extract model name (e.g., "gpt-5-mini_response" -> "gpt-5-mini")
        model_name = response_col.replace('_response', '').upper()
        
        # Extract final answers
        df['extracted_answer'] = df[response_col].apply(extract_final_answer)
        
        # Calculate metrics
        correct = (df['extracted_answer'] == df[answer_col]).sum()
        total = len(df)
        abstentions = (df['extracted_answer'] == 'UNKNOWN').sum()
        wrong = total - correct - abstentions
        
        accuracy_pct = (correct / total * 100) if total > 0 else 0
        abstention_rate = (abstentions / total * 100) if total > 0 else 0
        
        metrics_by_llm[model_name] = {
            'Total Questions': total,
            'Correct': correct,
            'Abstentions': abstentions,
            'Wrong': wrong,
            'Accuracy (%)': round(accuracy_pct, 2),
            'Abstention Rate (%)': round(abstention_rate, 2)
        }
    
    return metrics_by_llm

def aggregate_owl2bench_results(results_dir, hop, output_dir):
    """Aggregate OWL2Bench results for a specific hop."""
    print(f"\nProcessing OWL2Bench {hop}...")
    
    hop_dir = Path(results_dir) / f"OWL2Bench_{hop}"
    strategies = {
        'abstract': 'ABSTRACT',
        'nl': 'NL',
        'ttl': 'TTL'
    }
    
    all_records = []
    
    for file_prefix, strategy_display_name in strategies.items():
        results_file = hop_dir / f"{file_prefix}_results_FINAL.csv"
        
        if not results_file.exists():
            print(f"  ⚠️  Missing: {results_file}")
            continue
        
        metrics_by_llm = process_results_file(results_file, f"{strategy_display_name} Results")
        
        for llm_name, metrics in metrics_by_llm.items():
            record = {
                'Subset': strategy_display_name,
                'Translation Strategy': llm_name,
                'Total Questions': metrics['Total Questions'],
                'Correct': metrics['Correct'],
                'Abstentions (UNKNOWN)': metrics['Abstentions'],
                'Wrong': metrics['Wrong'],
                'Accuracy (%)': metrics['Accuracy (%)'],
                'Abstention Rate (%)': metrics['Abstention Rate (%)'],
                'Avg Question Complexity (words)': 0  # Not in source data
            }
            all_records.append(record)
    
    if all_records:
        result_df = pd.DataFrame(all_records)
        output_file = Path(output_dir) / f"OWL2Bench_{hop}_Accuracy_Report.csv"
        result_df.to_csv(output_file, index=False, quotechar='"')
        print(f"  ✓ Saved: {output_file}")
        print(f"\n  Results Summary:")
        print(result_df.to_string(index=False))
        return result_df
    else:
        print(f"  ✗ No results generated for {hop}")
        return None

def main():
    results_dir = "data/results"
    output_dir = "output"
    
    # Create output directory
    Path(output_dir).mkdir(exist_ok=True)
    
    all_hops = []
    
    for hop in ['1hop', '2hop']:
        result_df = aggregate_owl2bench_results(results_dir, hop, output_dir)
        if result_df is not None:
            result_df['Hop'] = hop
            all_hops.append(result_df)
    
    # Create combined report
    if all_hops:
        print("\n" + "="*80)
        combined_df = pd.concat(all_hops, ignore_index=True)
        combined_file = Path(output_dir) / "OWL2Bench_Combined_Accuracy_Report.csv"
        combined_df.to_csv(combined_file, index=False, quotechar='"')
        print(f"✓ Combined report saved: {combined_file}")
        print(f"\n📊 Full OWL2Bench Results (All Hops):")
        print(combined_df.to_string(index=False))

if __name__ == '__main__':
    main()
