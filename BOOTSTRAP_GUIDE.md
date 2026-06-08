# Bootstrap Confidence Intervals - Quick Start Guide

## What is Bootstrapping?

Bootstrapping is a **resampling technique** that estimates the distribution of a statistic (like accuracy) by repeatedly sampling *with replacement* from your observed data. For your small 45-question sample, this is crucial for determining if your accuracy scores are statistically significant.

## Why It Matters for Your Results

Your datasets have varying sizes:
- **Accuracy_Report_MC.csv**: 45 questions per strategy (⚠️ small sample)
- **Accuracy_Report.csv**: 65-129 questions per subset (more stable)

**Small samples = wide confidence intervals = higher uncertainty**

## What You'll Get

For each accuracy metric, the script calculates:

| Metric | What It Means |
|--------|---------------|
| **Point Estimate** | Your observed accuracy (e.g., 8.89%) |
| **95% CI** | [Lower%, Upper%] - Where true accuracy likely falls |
| **CI Width** | Width of the confidence interval (narrower = better) |
| **Margin of Error** | ±% precision of your estimate |
| **Std Error** | Measure of uncertainty in your estimates |
| **Bias** | Whether bootstrap mean differs from observed |

## How to Run

### For Accuracy_Report_MC.csv (45 questions - small sample):
```bash
source corev/bin/activate
cd /Users/karthikeyang/Vs\ code\ files/CORE-LLM-Bench-master

python scripts/bootstrap_confidence_intervals.py \
  --input-file output/Accuracy_Report_MC.csv \
  --output-dir bootstrap_results_MC \
  --n-iterations 10000 \
  --ci 0.95 \
  --report-name Accuracy_MC
```

### For Accuracy_Report.csv (larger samples):
```bash
python scripts/bootstrap_confidence_intervals.py \
  --input-file output/Accuracy_Report.csv \
  --output-dir bootstrap_results \
  --n-iterations 10000 \
  --ci 0.95 \
  --report-name Accuracy
```

### Run Both Analysis:
```bash
bash run_bootstrap_analysis.sh
```

## Understanding the Output

### Example Output:
```
Entailed - FORMAL
  Accuracy: 93.85% [95% CI: 89.23% - 97.31%]
  Margin of Error: ±3.75%
  ✓✓ Large sample size | Narrow CI - good precision
```

**This means:**
- Your observed accuracy is 93.85%
- You can be 95% confident the true accuracy is between 89.23% and 97.31%
- The margin of error is ±3.75%

### Small vs Large Sample Effects:

**Small Sample (45 questions):**
```
FORMAL: 8.89% [0.00% - 25.00%]  ← Very wide CI!
```
⚠️ Cannot draw firm conclusions - accuracy could be much higher or lower

**Larger Sample (65 questions):**
```
Entailed - FORMAL: 93.85% [89.23% - 97.31%]  ← Narrow CI
```
✓ Much more confidence in the estimate

## Interpreting Confidence Intervals

### 1. **Width of CI matters:**
- `[92%, 94%]` = Very precise estimate (width = 2%)
- `[80%, 95%]` = Wide estimate, high uncertainty (width = 15%)

### 2. **Comparing two strategies:**
- Non-overlapping CIs → **Statistically different** ✓
  - FORMAL: [89%, 97%]
  - DIRECT: [55%, 70%]
  - → Clearly different
  
- Overlapping CIs → **Cannot conclude significant difference**
  - FORMAL: [89%, 97%]
  - CONTEXTUAL: [75%, 90%]
  - → Overlap, could be same

### 3. **Sample size matters:**
- n = 45 → expect ±15-20% margin of error
- n = 65 → expect ±10-12% margin of error
- n = 129 → expect ±7-8% margin of error

## Bootstrap Algorithm Explained

```
For each translation strategy:
  1. Treat results as binary: [1,1,1,...,0,0,0] (1=correct, 0=wrong)
  2. Resample WITH replacement 10,000 times
  3. Calculate accuracy for each resample
  4. Get distribution of 10,000 bootstrap accuracies
  5. Take 2.5th and 97.5th percentiles → 95% CI
```

## Files Generated

- `bootstrap_results_MC/Accuracy_MC_bootstrap_summary.txt` - Human-readable report
- `bootstrap_results_MC/Accuracy_MC_bootstrap_results.json` - Machine-readable results
- Console output with quick summary

## Key Insights to Look For

After running the analysis, ask:

1. **Are my high accuracies within a narrow CI?**
   - YES → Results are stable and reliable
   - NO → Consider running more experiments

2. **Which strategies have the widest CIs?**
   - These are most uncertain and need more investigation

3. **Do different subsets (Entailed vs Non-entailed) show different patterns?**
   - Non-overlapping CIs = meaningful difference between subsets

4. **How does margin of error compare to domain requirements?**
   - Is ±8% acceptable for your use case?
   - Or do you need ±3%?

## Statistical Interpretation

- **95% Confidence Level**: If you repeated this experiment 100 times, ~95 of those CIs would contain the true parameter
- **Bias < 0.5%**: Negligible - bootstrap estimate is reliable
- **Standard Error decreasing**: More iterations = more stable estimates

## References

- Efron & Tibshirani (1993). *An Introduction to the Bootstrap*
- Davison & Hinkley (1997). *Bootstrap Methods and Their Applications*
- Carpenter & Bithell (2000). "Bootstrap confidence intervals"

## Common Pitfalls & Solutions

| Problem | Solution |
|---------|----------|
| Very wide CI (45 questions) | Run more experiments to get n > 100 |
| Overlapping CIs between strategies | Run larger sample to clarify difference |
| Bias > 1% | Increase bootstrap iterations (try 20000) |

---

**Next Step**: Run the analysis and examine where you have the widest confidence intervals. These are your priorities for improvement!
