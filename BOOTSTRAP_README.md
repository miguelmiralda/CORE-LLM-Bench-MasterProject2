# Bootstrap Confidence Intervals for Accuracy Analysis

## Quick Start

### Run All Analyses (Recommended)
```bash
cd "/Users/karthikeyang/Vs code files/CORE-LLM-Bench-master"
bash run_bootstrap_analysis.sh
```

This will:
1. Analyze `output/Accuracy_Report_MC.csv` (45 samples)
2. Analyze `output/Accuracy_Report.csv` (65-129 samples)
3. Generate comparison reports
4. Display summaries to console

### Run Individual Analysis

**Small sample (45 questions):**
```bash
source corev/bin/activate
python scripts/bootstrap_confidence_intervals.py \
  --input-file output/Accuracy_Report_MC.csv \
  --output-dir bootstrap_results_MC \
  --n-iterations 10000
```

**Larger samples (65-129 questions):**
```bash
python scripts/bootstrap_confidence_intervals.py \
  --input-file output/Accuracy_Report.csv \
  --output-dir bootstrap_results
```

**Generate comparison report:**
```bash
python scripts/bootstrap_comparison.py \
  --input-json bootstrap_results/Accuracy_bootstrap_results.json \
  --output-file bootstrap_results/comparison_report.txt
```

## What Gets Generated

### Files Created:
```
bootstrap_results_MC/
├── Accuracy_MC_bootstrap_summary.txt       # Human-readable report
├── Accuracy_MC_bootstrap_results.json      # Machine-readable results

bootstrap_results/
├── Accuracy_bootstrap_summary.txt          # Human-readable report
├── Accuracy_bootstrap_results.json         # Machine-readable results
└── Accuracy_comparison_report.txt          # Strategy comparisons
```

## Understanding Your Results

### Interpretation Examples

**Small Sample (45 questions):**
```
FORMAL
  Accuracy: 8.89% [95% CI: 0.00% - 25.00%]
  Margin of Error: ±12.50%
  ⚠️ Small sample size - high variability expected
```
**What this means:**
- ❌ Cannot draw firm conclusions - CI is too wide
- ❌ Accuracy could be anywhere from 0% to 25%
- ✓ Run more experiments (target: n > 100)

**Larger Sample (65 questions):**
```
Entailed - FORMAL
  Accuracy: 93.85% [95% CI: 89.23% - 97.31%]
  Margin of Error: ±3.75%
  ✓✓ Large sample size - results more stable
```
**What this means:**
- ✓ High confidence in the estimate
- ✓ True accuracy is very likely between 89% and 97%
- ✓ Margin of error is ±3.75% (acceptable precision)

## Bootstrap Algorithm Overview

The script performs **10,000 bootstrap iterations**:

```
For each strategy:
  Original data: 4 correct, 41 wrong (for 8.89% example)
  
  Bootstrap iteration 1:
    - Resample 45 values with replacement
    - Might get: 3 correct, 42 wrong → accuracy = 6.67%
  
  Bootstrap iteration 2:
    - Resample 45 values with replacement  
    - Might get: 6 correct, 39 wrong → accuracy = 13.33%
  
  ... repeat 10,000 times ...
  
  Result: Distribution of 10,000 accuracies
  Take 2.5th percentile → CI lower bound
  Take 97.5th percentile → CI upper bound
```

This gives you the **actual distribution** of accuracy estimates.

## Key Insights from Small vs Large Samples

### Your Data Analysis:

**Report A (45 samples per strategy):**
- Total: 4 samples across 4 strategies
- Each strategy has only 45 questions
- Expected CI width: ±15-20%
- **Action:** Run more experiments before drawing conclusions

**Report B (65-129 samples per subset):**
- Better sample sizes (65-129 questions)
- Subset analysis enables deeper insights
- Expected CI width: ±7-12%
- **Action:** Can compare strategies with more confidence

## Comparison Logic

### When are two strategies significantly different?

**✓ Significantly Different (non-overlapping CIs):**
```
Strategy A: [85%, 92%]
Strategy B: [93%, 98%]
→ Clearly different (no overlap)
```

**✗ Cannot Conclude Different (overlapping CIs):**
```
Strategy A: [85%, 95%]
Strategy B: [90%, 100%]
→ Overlap from 90-95% (need more data)
```

## Sample Size Recommendations

To achieve different levels of precision:

| Target Precision | Required Sample Size | CI Width |
|------------------|---------------------|----------|
| ±10% | ~100 samples | 20% |
| ±5% | ~400 samples | 10% |
| ±3% | ~1000 samples | 6% |
| ±1% | ~10,000 samples | 2% |

**Your current situation:**
- n = 45 → margin of error ±12-15% (too wide)
- n = 65-129 → margin of error ±7-10% (acceptable)

**Recommendation:** Target n ≥ 200 per strategy for ±5% precision

## Understanding Output Metrics

| Metric | Formula | Interpretation |
|--------|---------|-----------------|
| **Point Estimate** | correct/total × 100 | Your observed accuracy |
| **95% CI** | [2.5th %, 97.5th %] | Likely range for true accuracy |
| **Margin of Error** | 1.96 × SE | ±value around point estimate |
| **Standard Error** | std(bootstrap_samples) | Variability of estimates |
| **CI Width** | CI_upper - CI_lower | Precision (narrow = good) |
| **Bias** | mean(bootstrap) - observed | Estimation accuracy |

## Advanced: Interpreting Bias

**Normal bias:** < 0.5%
```
Observed: 50.00%
Bootstrap mean: 50.15%
Bias: 0.15% ✓ Good
```

**Large bias:** > 1%
```
Observed: 50.00%
Bootstrap mean: 52.50%
Bias: 2.50% ⚠️ Investigate
→ Increase bootstrap iterations to 20000
→ Check for data entry errors
```

## Common Questions

### Q: What does "95% CI" mean?
**A:** If you repeated your experiment 100 times and calculated CI each time, ~95 of those intervals would contain the true accuracy. It's NOT "95% probability the true value is in this range."

### Q: Can I compare overlapping CIs?
**A:** Not definitively. Overlapping CIs suggest you cannot conclude significant difference at 95% confidence. Consider:
- Running more experiments
- Doing a direct hypothesis test
- Using Bayesian methods

### Q: Why bootstrap instead of normal approximation?
**A:** Bootstrap works for any distribution and doesn't assume normality. For binary data (correct/wrong), it's more reliable than assuming normal distribution.

### Q: How many bootstrap iterations do I need?
**A:** 10,000 is standard for CI estimation. For p-values, use 100,000+. Current script uses 10,000 - good balance.

### Q: Should I use 95% or 99% CI?
**A:** 95% is standard. Use 99% only if you need stricter confidence (wider CI). Script default is 95%.

## Troubleshooting

### Issue: Very wide CI (width > 25%)
**Cause:** Small sample size
**Solution:** Run more experiments (target n > 200)

### Issue: Bias > 2%
**Cause:** Insufficient bootstrap iterations or numerical instability
**Solution:** Increase iterations to 20000 or check data quality

### Issue: All CIs are identical
**Cause:** Data is too clean or perfectly balanced
**Solution:** Verify accuracy calculation and data format

## Files Structure

```
CORE-LLM-Bench-master/
├── scripts/
│   ├── bootstrap_confidence_intervals.py  # Main analysis script
│   ├── bootstrap_comparison.py            # Comparison script
│   └── explanations_fix.py                # JSON repair utility
├── output/
│   ├── Accuracy_Report_MC.csv             # Small sample (45 each)
│   ├── Accuracy_Report.csv                # Larger samples (65-129)
│   ├── Explanations.json                  # (will be fixed)
│   └── SPARQL_questions.csv
├── bootstrap_results_MC/                  # Small sample analysis
│   ├── Accuracy_MC_bootstrap_summary.txt
│   └── Accuracy_MC_bootstrap_results.json
├── bootstrap_results/                     # Full analysis
│   ├── Accuracy_bootstrap_summary.txt
│   ├── Accuracy_bootstrap_results.json
│   └── Accuracy_comparison_report.txt
├── BOOTSTRAP_GUIDE.md                     # Detailed guide
├── BOOTSTRAP_README.md                    # This file
└── run_bootstrap_analysis.sh              # Automation script
```

## References & Further Reading

1. **Efron & Tibshirani (1993)** - "An Introduction to the Bootstrap"
   - Classic reference, mathematical foundations
   
2. **Davison & Hinkley (1997)** - "Bootstrap Methods and Their Applications"
   - Practical applications
   
3. **Carpenter & Bithell (2000)** - "Bootstrap confidence intervals: when, which, what?"
   - Medical statistics perspective
   
4. **Chernick (2008)** - "Bootstrap Methods: A Practitioner's Guide"
   - Applied focus

## Next Steps

1. **Run the analysis:**
   ```bash
   bash run_bootstrap_analysis.sh
   ```

2. **Review the reports:**
   ```bash
   cat bootstrap_results_MC/Accuracy_MC_bootstrap_summary.txt
   cat bootstrap_results/Accuracy_bootstrap_summary.txt
   ```

3. **Identify improvement areas:**
   - Which strategies have the widest CIs?
   - Which need more experiments?
   - What precision is acceptable for your domain?

4. **Plan next experiments:**
   - Target strategies with wide CIs
   - Aim for n > 200 per strategy
   - Re-run analysis quarterly

## Questions?

For detailed statistical explanations, see [BOOTSTRAP_GUIDE.md](BOOTSTRAP_GUIDE.md)

For technical details, see docstrings in:
- `scripts/bootstrap_confidence_intervals.py`
- `scripts/bootstrap_comparison.py`
