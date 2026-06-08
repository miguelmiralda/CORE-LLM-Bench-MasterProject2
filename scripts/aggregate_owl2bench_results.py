#!/usr/bin/env python3
"""
Aggregate OWL2Bench results across different verbalization strategies and hops.
Creates accuracy reports similar to FamilyOWL reports.
"""

import pandas as pd
import argparse
import json
from pathlib import Path

def calculate_accuracy_metrics(df, strategy_col, answer_col):
    """Calculate accuracy metrics for a strategy."""
    metrics = []
    
    for strategy in df[strategy_col].unique():
        subset_df = df[df[strategy_col] == strategy]
        
        # Count correct answers (match between answer and LLM response)
        correct = (subset_df[answer_col] == subset_df['Final_Answer']).sum()
        total = len(subset_df)
        
        # Calculate abstentions (UNKNOWN responses)
        abstentions = (subset_df['Final_Answer'] == 'UNKNOWN').sum()
        
        # Calculate wrong answers
        wrong = total - correct - abstentions
        
        # Accuracy percentage
        accuracy_pct = (correct / total * 100) if total > 0 else 0
        abstention_rate = (abstentions / total * 100) if total > 0 else 0
        
        # Average question complexity
        avg_complexity = subset_df['Question_Length'].mean() if 'Question_Length' in subset_df else 0
        
        metrics.append({
            'Translation Strategy': strategy,
            'Total Questions': total,
            'Correct': correct,
            'Abstentions (UNKNOWN)': abstentions,
            'Wrong': wrong,
            'Accuracy (%)': round(accuracy_pct, 2),
            'Abstention Rate (%)': round(abstention_rate, 2),
            'Avg Question Complexity (words)': round(avg_complexity, 2)
        })
    
    return metrics

def process_owl2bench_results(dataset_dir, hop, output_file):
    """Process OWL2Bench results for a specific hop."""
    print(f"Processing OWL2Bench {hop}...")
    
    results_dir = Path(dataset_dir) / f"OWL2Bench_{hop}"
    
    all_metrics = []
    
    # Process each verbalization strategy
    strategies = {
        'abstract': 'ABSTRACT',
        'nl': 'NL',
        'ttl': 'TTL'
    }
    
    for file_prefix, strategy_name in strategies.items():
        results_file = results_dir / f"{file_prefix}_results_FINAL.csv"
        
        if not results_file.exists():
            print(f"  ⚠️  Missing: {results_file}")
            continue
        
        print(f"  Loading: {strategy_name} results...")
        df = pd.read_csv(results_file)
        
        # Extract final answers from LLM responses
        # Assuming format similar to: "ANSWER: TRUE\nCONFIDENCE: 0.9\n..."
        def extract_answer(response_str):
            if pd.isna(response_str):
                return 'UNKNOWN'
            response_str = str(response_str).upper()
            if 'TRUE' in response_str and 'ANSWER' in response_str:
                return 'TRUE'
            elif 'FALSE' in response_str and 'ANSWER' in response_str:
                return 'FALSE'
            else:
                return 'UNKNOWN'
        
        # Process each LLM model
        models = []
        for col in df.columns:
            if '_response' in col and col.endswith('_response'):
                model_name = col.replace('_response', '')
                models.append(model_name)
        
        for model in models:
            response_col = f'{model}_response'
            answer_col = 'Answer'
            
            if response_col not in df.columns or answer_col not in df.columns:
                continue
            
            # Extract answers
            df['Final_Answer'] = df[response_col].apply(extract_answer)
            
            # Calculate metrics
            correct = (df['Final_Answer'] == df[answer_col]).sum()
            total = len(df)
            abstentions = (df['Final_Answer'] == 'UNKNOWN').sum()
            wrong = total - correct - abstentions
            accuracy_pct = (correct / total * 100) if total > 0 else 0
            abstention_rate = (abstentions / total * 100) if total > 0 else 0
            
            all_metrics.append({
                'Subset': f'{strategy_name}',
                'Translation Strategy': model.upper(),
                'Total Questions': total,
                'Correct': correct,
                'Abstentions (UNKNOWN)': abstentions,
                'Wrong': wrong,
                'Accuracy (%)': round(accuracy_pct, 2),
                'Abstention Rate (%)': round(abstention_rate, 2),
                'Avg Question Complexity (words)': 0  # Not easily extracted
            })
    
    # Save results
    if all_metrics:
        result_df = pd.DataFrame(all_metrics)
        result_df.to_csv(output_file, index=False, quotechar='"')
        print(f"  ✓ Results saved to: {output_file}")
        print(f"\n{result_df.to_string(index=False)}\n")
        return result_df
    else:
        print(f"  ✗ No metrics generated for {hop}")
        return None

def main():
    parser = argparse.ArgumentParser(description='Aggregate OWL2Bench results')
    parser.add_argument('--dataset-dir', default='data/results', 
                       help='Base directory containing results')
    parser.add_argument('--output-dir', default='output',
                       help='Output directory for aggregated results')
    parser.add_argument('--hops', nargs='+', default=['1hop', '2hop'],
                       help='Hops to process')
    
    args = parser.parse_args()
    
    # Create output directory if needed
    Path(args.output_dir).mkdir(exist_ok=True)
    
    all_results = []
    
    for hop in args.hops:
        output_file = f"{args.output_dir}/OWL2Bench_{hop}_Accuracy_Report.csv"
        result_df = process_owl2bench_results(args.dataset_dir, hop, output_file)
        if result_df is not None:
            result_df['Hop'] = hop
            all_results.append(result_df)
    
    # Combine all hops
    if all_results:
        combined_df = pd.concat(all_results, ignore_index=True)
        combined_file = f"{args.output_dir}/OWL2Bench_Combined_Accuracy_Report.csv"
        combined_df.to_csv(combined_file, index=False, quotechar='"')
        print(f"✓ Combined results saved to: {combined_file}")

if __name__ == '__main__':
    main()
