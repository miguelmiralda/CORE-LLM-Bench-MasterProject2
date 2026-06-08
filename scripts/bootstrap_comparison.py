"""
Advanced Bootstrap Analysis: Strategy Comparison & Visualization

Provides statistical comparisons between different translation strategies
and generates visualizations of confidence intervals.
"""

import json
from pathlib import Path
import pandas as pd
import numpy as np
from typing import Dict, List
import argparse


class BootstrapComparison:
    """Compare bootstrap results between different strategies."""
    
    @staticmethod
    def generate_comparison_table(results: Dict) -> pd.DataFrame:
        """Create a comparison table from bootstrap results."""
        data = []
        
        for analysis in results['analyses']:
            data.append({
                'Strategy': analysis['identifier'],
                'Sample Size': analysis['total'],
                'Accuracy (%)': analysis['point_estimate_percent'],
                'CI Lower (%)': analysis['ci_lower_percent'],
                'CI Upper (%)': analysis['ci_upper_percent'],
                'CI Width': analysis['ci_width'],
                'Margin of Error (±%)': analysis['margin_of_error'],
                'Std Error': analysis['standard_error'],
            })
        
        return pd.DataFrame(data)
    
    @staticmethod
    def identify_best_performers(results: Dict, threshold_ci_width: float = 15) -> List[Dict]:
        """
        Identify best performing strategies based on accuracy and CI width.
        
        Strategies are ranked by:
        1. High accuracy (point estimate)
        2. Narrow CI width (high precision)
        """
        performers = []
        
        for analysis in results['analyses']:
            score = {
                'strategy': analysis['identifier'],
                'accuracy': analysis['point_estimate_percent'],
                'ci_width': analysis['ci_width'],
                'ci_lower': analysis['ci_lower_percent'],
                'ci_upper': analysis['ci_upper_percent'],
                'margin_of_error': analysis['margin_of_error'],
                'rank_score': (
                    analysis['point_estimate_percent'] 
                    - (analysis['ci_width'] / 10)  # Penalize wide CIs
                ),
                'interpretation': 'High accuracy, narrow CI' if analysis['ci_width'] < threshold_ci_width 
                                 else 'High accuracy, wide CI'
            }
            performers.append(score)
        
        # Sort by rank score descending
        performers.sort(key=lambda x: x['rank_score'], reverse=True)
        
        return performers
    
    @staticmethod
    def check_statistical_significance(
        analysis1: Dict,
        analysis2: Dict
    ) -> Dict[str, bool | str]:
        """
        Check if two strategies are statistically significantly different.
        
        Logic: If confidence intervals don't overlap, they're significantly different.
        """
        ci1_lower = analysis1['ci_lower_percent']
        ci1_upper = analysis1['ci_upper_percent']
        ci2_lower = analysis2['ci_lower_percent']
        ci2_upper = analysis2['ci_upper_percent']
        
        # Check overlap
        overlaps = not (ci1_upper < ci2_lower or ci2_upper < ci1_lower)
        
        if not overlaps:
            if analysis1['point_estimate_percent'] > analysis2['point_estimate_percent']:
                conclusion = f"{analysis1['identifier']} is significantly better"
            else:
                conclusion = f"{analysis2['identifier']} is significantly better"
        else:
            conclusion = "No significant difference detected (CIs overlap)"
        
        return {
            'analysis1': analysis1['identifier'],
            'analysis2': analysis2['identifier'],
            'cis_overlap': overlaps,
            'statistically_significant': not overlaps,
            'conclusion': conclusion
        }
    
    @staticmethod
    def generate_comparison_report(
        results: Dict,
        output_path: Path
    ) -> None:
        """Generate a detailed comparison report."""
        with open(output_path, 'w') as f:
            f.write("=" * 100 + "\n")
            f.write("BOOTSTRAP ANALYSIS - STRATEGY COMPARISON REPORT\n")
            f.write("=" * 100 + "\n\n")
            
            # Comparison table
            df = BootstrapComparison.generate_comparison_table(results)
            f.write("SUMMARY TABLE:\n")
            f.write("-" * 100 + "\n")
            f.write(df.to_string(index=False))
            f.write("\n\n")
            
            # Best performers
            f.write("=" * 100 + "\n")
            f.write("BEST PERFORMERS (ranked by accuracy and CI width)\n")
            f.write("=" * 100 + "\n\n")
            
            performers = BootstrapComparison.identify_best_performers(results)
            for i, perf in enumerate(performers, 1):
                f.write(f"{i}. {perf['strategy']}\n")
                f.write(f"   Accuracy: {perf['accuracy']:.2f}% [±{perf['margin_of_error']:.2f}%]\n")
                f.write(f"   CI Range: [{perf['ci_lower']:.2f}%, {perf['ci_upper']:.2f}%]\n")
                f.write(f"   Status: {perf['interpretation']}\n")
                f.write(f"   Rank Score: {perf['rank_score']:.2f}\n\n")
            
            # Pairwise comparisons for small datasets
            if len(results['analyses']) <= 10:
                f.write("=" * 100 + "\n")
                f.write("PAIRWISE STATISTICAL COMPARISONS\n")
                f.write("=" * 100 + "\n\n")
                
                analyses = results['analyses']
                for i in range(len(analyses)):
                    for j in range(i + 1, len(analyses)):
                        comparison = BootstrapComparison.check_statistical_significance(
                            analyses[i],
                            analyses[j]
                        )
                        f.write(f"{comparison['analysis1']} vs {comparison['analysis2']}\n")
                        f.write(f"  CIs Overlap: {comparison['cis_overlap']}\n")
                        f.write(f"  Significant Difference: {comparison['statistically_significant']}\n")
                        f.write(f"  Conclusion: {comparison['conclusion']}\n\n")
            
            # Recommendations
            f.write("=" * 100 + "\n")
            f.write("RECOMMENDATIONS\n")
            f.write("=" * 100 + "\n\n")
            
            performers = BootstrapComparison.identify_best_performers(results)
            best = performers[0]
            
            f.write(f"1. TOP STRATEGY: {best['strategy']}\n")
            f.write(f"   - Accuracy: {best['accuracy']:.2f}%\n")
            f.write(f"   - Margin of Error: ±{best['margin_of_error']:.2f}%\n\n")
            
            # Identify strategies needing improvement
            wide_ci_strategies = [
                a for a in results['analyses'] 
                if a['ci_width'] > 20
            ]
            
            if wide_ci_strategies:
                f.write("2. STRATEGIES NEEDING MORE DATA (wide confidence intervals):\n")
                for strategy in wide_ci_strategies:
                    f.write(f"   - {strategy['identifier']}: CI width = {strategy['ci_width']:.2f}%\n")
                f.write("   → Consider running more experiments to narrow these intervals\n\n")
            
            # Sample size recommendations
            f.write("3. SAMPLE SIZE RECOMMENDATIONS:\n")
            f.write("   Current sample sizes and their precision:\n")
            for analysis in results['analyses']:
                f.write(f"   - {analysis['total']} samples → ±{analysis['margin_of_error']:.2f}% margin of error\n")
            
            f.write("\n   To achieve ±5% margin of error: need ~400 samples per strategy\n")
            f.write("   To achieve ±3% margin of error: need ~1000 samples per strategy\n\n")
            
            f.write("=" * 100 + "\n")
            f.write("INTERPRETATION NOTES\n")
            f.write("=" * 100 + "\n\n")
            f.write("""
- A 95% Confidence Interval means: if you repeated the experiment 100 times,
  approximately 95 of those intervals would contain the true accuracy.

- Non-overlapping CIs indicate statistically significant differences at 95% confidence.

- Overlapping CIs do NOT mean results are the same - they mean you need more data
  to determine if there's truly a difference.

- Margin of Error (±X%) is the half-width of the CI. For example:
  "90% accuracy with ±5% margin of error" means [85%, 95%] CI.

- The relationship between sample size and margin of error is: ME ≈ 1.96 * sqrt(p(1-p)/n)
  where p is the proportion (accuracy) and n is sample size.
""")


def main():
    parser = argparse.ArgumentParser(
        description="Generate comparison reports from bootstrap results"
    )
    parser.add_argument(
        "--input-json",
        type=Path,
        required=True,
        help="Path to bootstrap results JSON file"
    )
    parser.add_argument(
        "--output-file",
        type=Path,
        default=None,
        help="Output file for comparison report"
    )
    
    args = parser.parse_args()
    
    if not args.input_json.exists():
        raise FileNotFoundError(f"Input file not found: {args.input_json}")
    
    # Load results
    with open(args.input_json, 'r') as f:
        results = json.load(f)
    
    # Determine output path
    if args.output_file is None:
        output_file = args.input_json.parent / f"{args.input_json.stem}_comparison.txt"
    else:
        output_file = args.output_file
    
    # Generate report
    print(f"Generating comparison report from: {args.input_json}")
    BootstrapComparison.generate_comparison_report(results, output_file)
    print(f"✓ Comparison report saved to: {output_file}")
    
    # Print summary to console
    print("\n" + "=" * 80)
    print("STRATEGY COMPARISON SUMMARY")
    print("=" * 80)
    
    performers = BootstrapComparison.identify_best_performers(results)
    print(f"\nTop 3 Strategies:\n")
    for i, perf in enumerate(performers[:3], 1):
        print(f"{i}. {perf['strategy']}")
        print(f"   Accuracy: {perf['accuracy']:.2f}% [±{perf['margin_of_error']:.2f}%]")
        print(f"   Status: {perf['interpretation']}\n")


if __name__ == "__main__":
    main()
