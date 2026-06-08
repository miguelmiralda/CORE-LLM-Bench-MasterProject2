"""
Bootstrap Confidence Intervals for Accuracy Metrics

This script calculates confidence intervals for accuracy scores using bootstrap resampling.
This is particularly important for small sample sizes to determine if high accuracy scores
are statistically significant or due to random chance.

Reference: Efron & Tibshirani (1993) - An Introduction to the Bootstrap
"""

import argparse
from pathlib import Path
import pandas as pd
import numpy as np
from typing import Dict, Tuple, List
import json
from datetime import datetime
import warnings

warnings.filterwarnings('ignore')


class BootstrapAnalyzer:
    """Perform bootstrap resampling for confidence intervals."""
    
    def __init__(self, n_iterations: int = 10000, ci: float = 0.95):
        """
        Args:
            n_iterations: Number of bootstrap samples to generate
            ci: Confidence interval level (0.95 = 95% CI)
        """
        self.n_iterations = n_iterations
        self.ci = ci
        self.alpha = 1 - ci  # For two-tailed test
    
    def bootstrap_accuracy(
        self, 
        correct: int, 
        total: int,
        random_state: int | None = None
    ) -> Dict[str, float]:
        """
        Calculate bootstrap confidence interval for a single accuracy metric.
        
        Treats the problem as a Bernoulli process:
        - Generate bootstrap samples by resampling with replacement
        - Calculate accuracy for each bootstrap sample
        - Compute confidence interval from the distribution
        
        Args:
            correct: Number of correct predictions
            total: Total number of samples
            random_state: Random seed for reproducibility
            
        Returns:
            Dictionary with statistics
        """
        if random_state is not None:
            np.random.seed(random_state)
        
        # Generate bootstrap samples
        # Each sample is binary: 1 (correct) or 0 (incorrect)
        successes = np.ones(correct)
        failures = np.zeros(total - correct)
        original_data = np.concatenate([successes, failures])
        
        bootstrap_accuracies = []
        
        for _ in range(self.n_iterations):
            # Resample with replacement
            bootstrap_sample = np.random.choice(original_data, size=total, replace=True)
            bootstrap_accuracy = np.mean(bootstrap_sample) * 100
            bootstrap_accuracies.append(bootstrap_accuracy)
        
        bootstrap_accuracies = np.array(bootstrap_accuracies)
        
        # Calculate percentile-based confidence interval
        lower_percentile = (self.alpha / 2) * 100
        upper_percentile = (1 - self.alpha / 2) * 100
        
        ci_lower = np.percentile(bootstrap_accuracies, lower_percentile)
        ci_upper = np.percentile(bootstrap_accuracies, upper_percentile)
        
        # Point estimate
        point_estimate = (correct / total) * 100
        
        # Standard error
        se = np.std(bootstrap_accuracies)
        
        # Bias (bootstrap estimate - true estimate)
        bias = np.mean(bootstrap_accuracies) - point_estimate
        
        return {
            'point_estimate': point_estimate,
            'ci_lower': ci_lower,
            'ci_upper': ci_upper,
            'ci_width': ci_upper - ci_lower,
            'standard_error': se,
            'bias': bias,
            'ci_level': self.ci,
            'n_bootstrap': self.n_iterations,
            'correct': correct,
            'total': total,
            'bootstrap_samples': bootstrap_accuracies
        }
    
    def compare_accuracies(
        self, 
        group1_results: Dict,
        group2_results: Dict,
        random_state: int | None = None
    ) -> Dict[str, float]:
        """
        Compare two accuracy groups to see if confidence intervals overlap.
        
        Args:
            group1_results: Bootstrap results from first group
            group2_results: Bootstrap results from second group
            random_state: Random seed
            
        Returns:
            Comparison statistics
        """
        if random_state is not None:
            np.random.seed(random_state)
        
        acc1 = group1_results['bootstrap_samples']
        acc2 = group2_results['bootstrap_samples']
        
        # Difference in accuracies
        differences = acc1 - acc2
        
        diff_ci_lower = np.percentile(differences, (self.alpha / 2) * 100)
        diff_ci_upper = np.percentile(differences, (1 - self.alpha / 2) * 100)
        
        # P-value approximation: does CI for difference include 0?
        ci_includes_zero = diff_ci_lower <= 0 <= diff_ci_upper
        
        return {
            'mean_difference': np.mean(differences),
            'difference_ci_lower': diff_ci_lower,
            'difference_ci_upper': diff_ci_upper,
            'ci_includes_zero': ci_includes_zero,
            'statistically_different': not ci_includes_zero,
            'ci_level': self.ci
        }


def analyze_accuracy_report(
    csv_path: Path,
    output_dir: Path,
    n_iterations: int = 10000,
    ci: float = 0.95,
    report_name: str = "Accuracy_Report"
) -> Dict:
    """
    Analyze an accuracy report CSV and generate bootstrap confidence intervals.
    
    Args:
        csv_path: Path to accuracy CSV file
        output_dir: Directory to save results
        n_iterations: Bootstrap iterations
        ci: Confidence interval level
        report_name: Name for output files
        
    Returns:
        Dictionary with all results
    """
    # Read CSV
    df = pd.read_csv(csv_path)
    
    # Clean numeric columns (handle European decimal format)
    numeric_cols = ['Total Questions', 'Correct', 'Abstentions (UNKNOWN)', 'Wrong']
    for col in numeric_cols:
        if col in df.columns:
            df[col] = df[col].astype(int)
    
    analyzer = BootstrapAnalyzer(n_iterations=n_iterations, ci=ci)
    results = {
        'metadata': {
            'source_file': str(csv_path),
            'analysis_date': datetime.now().isoformat(),
            'n_bootstrap_iterations': n_iterations,
            'ci_level': ci,
            'ci_percentage': f"{ci*100:.0f}%"
        },
        'analyses': []
    }
    
    # Process each row
    for idx, row in df.iterrows():
        correct = row['Correct']
        total = row['Total Questions']
        
        # Build identifier from available columns
        if 'Subset' in df.columns:
            identifier = f"{row['Subset']} - {row['Translation Strategy']}"
        else:
            identifier = row['Translation Strategy']
        
        # Run bootstrap
        bs_results = analyzer.bootstrap_accuracy(correct, total, random_state=42)
        
        analysis = {
            'identifier': identifier,
            'correct': int(correct),
            'total': int(total),
            'point_estimate_percent': round(bs_results['point_estimate'], 2),
            'ci_lower_percent': round(bs_results['ci_lower'], 2),
            'ci_upper_percent': round(bs_results['ci_upper'], 2),
            'ci_width': round(bs_results['ci_width'], 2),
            'standard_error': round(bs_results['standard_error'], 2),
            'bias': round(bs_results['bias'], 2),
            'margin_of_error': round(bs_results['standard_error'] * 1.96, 2),  # For 95% CI
            'interpretation': interpret_confidence_interval(
                bs_results['point_estimate'],
                bs_results['ci_lower'],
                bs_results['ci_upper'],
                total
            )
        }
        
        results['analyses'].append(analysis)
    
    return results


def interpret_confidence_interval(
    point_estimate: float,
    ci_lower: float,
    ci_upper: float,
    sample_size: int
) -> str:
    """Generate interpretation of confidence interval."""
    ci_width = ci_upper - ci_lower
    
    if sample_size <= 50:
        size_note = "⚠️  Small sample size - high variability expected"
    elif sample_size <= 100:
        size_note = "✓ Moderate sample size"
    else:
        size_note = "✓✓ Large sample size - results more stable"
    
    if ci_width > 20:
        precision_note = "Wide CI - low precision, high uncertainty"
    elif ci_width > 10:
        precision_note = "Moderate CI width - moderate uncertainty"
    else:
        precision_note = "Narrow CI - good precision"
    
    return f"{size_note} | {precision_note}"


def generate_summary_report(results: Dict, output_path: Path) -> None:
    """Generate a human-readable summary report."""
    with open(output_path, 'w') as f:
        f.write("=" * 80 + "\n")
        f.write("BOOTSTRAP CONFIDENCE INTERVALS FOR ACCURACY METRICS\n")
        f.write("=" * 80 + "\n\n")
        
        metadata = results['metadata']
        f.write(f"Analysis Date: {metadata['analysis_date']}\n")
        f.write(f"Source File: {metadata['source_file']}\n")
        f.write(f"Bootstrap Iterations: {metadata['n_bootstrap_iterations']}\n")
        f.write(f"Confidence Level: {metadata['ci_percentage']}\n\n")
        
        f.write("=" * 80 + "\n")
        f.write("RESULTS\n")
        f.write("=" * 80 + "\n\n")
        
        for i, analysis in enumerate(results['analyses'], 1):
            f.write(f"{i}. {analysis['identifier']}\n")
            f.write(f"   Correct: {analysis['correct']} / {analysis['total']}\n")
            f.write(f"   Accuracy: {analysis['point_estimate_percent']}%\n")
            f.write(f"   95% CI: [{analysis['ci_lower_percent']}%, {analysis['ci_upper_percent']}%]\n")
            f.write(f"   CI Width: {analysis['ci_width']}% (margin of error: ±{analysis['margin_of_error']}%)\n")
            f.write(f"   Std Error: {analysis['standard_error']:.2f}%\n")
            f.write(f"   Bias: {analysis['bias']:.4f}%\n")
            f.write(f"   {analysis['interpretation']}\n\n")
        
        f.write("=" * 80 + "\n")
        f.write("INTERPRETATION GUIDE\n")
        f.write("=" * 80 + "\n\n")
        f.write("""
1. POINT ESTIMATE: The observed accuracy from your data

2. CONFIDENCE INTERVAL (CI): The range where the true accuracy likely falls
   - 95% CI means if you repeated the experiment many times, ~95% of CIs 
     would contain the true accuracy
   - Narrow CI = more precise estimate (good)
   - Wide CI = less precise estimate (less certain)

3. STANDARD ERROR: Measures variability in your bootstrap samples
   - Larger SE = higher uncertainty
   - SE decreases with larger sample sizes (√n relationship)

4. MARGIN OF ERROR: ±value around point estimate at 95% confidence
   - Common interpretation: "Accuracy is X% ± Y%"

5. BIAS: Difference between bootstrap mean and observed accuracy
   - Usually small; large bias suggests estimation issues

SAMPLE SIZE IMPLICATIONS:
- n ≤ 50: Small samples, expect wide CIs, higher uncertainty
- 50 < n ≤ 100: Moderate samples
- n > 100: Large samples, narrower CIs, more stable estimates

COMPARING STRATEGIES:
- Non-overlapping CIs → statistically different at 95% confidence
- Overlapping CIs → cannot conclude significant difference
""")


def generate_json_report(results: Dict, output_path: Path) -> None:
    """Save results as JSON for programmatic use."""
    with open(output_path, 'w') as f:
        json.dump(results, f, indent=2)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Calculate bootstrap confidence intervals for accuracy metrics"
    )
    parser.add_argument(
        "--input-file",
        type=Path,
        required=True,
        help="Path to accuracy CSV file"
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("bootstrap_results"),
        help="Output directory for results"
    )
    parser.add_argument(
        "--n-iterations",
        type=int,
        default=10000,
        help="Number of bootstrap iterations (default: 10000)"
    )
    parser.add_argument(
        "--ci",
        type=float,
        default=0.95,
        help="Confidence interval level (0-1, default: 0.95)"
    )
    parser.add_argument(
        "--report-name",
        type=str,
        default="Accuracy_Report",
        help="Name for output files"
    )
    
    args = parser.parse_args()
    
    # Validate inputs
    if not args.input_file.exists():
        raise FileNotFoundError(f"Input file not found: {args.input_file}")
    
    if not (0 < args.ci < 1):
        raise ValueError("CI must be between 0 and 1")
    
    # Create output directory
    args.output_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"Analyzing: {args.input_file}")
    print(f"Bootstrap iterations: {args.n_iterations}")
    print(f"Confidence level: {args.ci * 100:.0f}%\n")
    
    # Run analysis
    results = analyze_accuracy_report(
        args.input_file,
        args.output_dir,
        n_iterations=args.n_iterations,
        ci=args.ci,
        report_name=args.report_name
    )
    
    # Generate reports
    summary_path = args.output_dir / f"{args.report_name}_bootstrap_summary.txt"
    json_path = args.output_dir / f"{args.report_name}_bootstrap_results.json"
    
    generate_summary_report(results, summary_path)
    generate_json_report(results, json_path)
    
    print(f"✓ Summary report: {summary_path}")
    print(f"✓ JSON results: {json_path}\n")
    
    # Print summary to console
    print("=" * 80)
    print("BOOTSTRAP ANALYSIS SUMMARY")
    print("=" * 80)
    for analysis in results['analyses']:
        print(f"\n{analysis['identifier']}")
        print(f"  Accuracy: {analysis['point_estimate_percent']}% "
              f"[95% CI: {analysis['ci_lower_percent']}% - {analysis['ci_upper_percent']}%]")
        print(f"  Margin of Error: ±{analysis['margin_of_error']}%")
        print(f"  {analysis['interpretation']}")


if __name__ == "__main__":
    main()
