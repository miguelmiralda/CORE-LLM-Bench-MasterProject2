#!/bin/bash

# Bootstrap Analysis Runner
# Runs bootstrap confidence interval analysis on both accuracy reports

set -e  # Exit on error

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Bootstrap Confidence Interval Analysis${NC}"
echo -e "${BLUE}========================================${NC}\n"

# Activate virtual environment
echo -e "${YELLOW}Activating virtual environment...${NC}"
source corev/bin/activate

# Ensure output directories exist
mkdir -p bootstrap_results_MC
mkdir -p bootstrap_results

# Analysis 1: Small sample (45 questions)
echo -e "\n${BLUE}[1/2] Analyzing Accuracy_Report_MC.csv (45 questions per strategy)${NC}"
echo -e "${YELLOW}This demonstrates the importance of bootstrapping for small samples...${NC}\n"

python scripts/bootstrap_confidence_intervals.py \
  --input-file output/Accuracy_Report_MC.csv \
  --output-dir bootstrap_results_MC \
  --n-iterations 10000 \
  --ci 0.95 \
  --report-name Accuracy_MC

echo -e "${GREEN}✓ MC analysis complete${NC}"

# Analysis 2: Larger samples
echo -e "\n${BLUE}[2/2] Analyzing Accuracy_Report.csv (65-129 questions per subset)${NC}"
echo -e "${YELLOW}Comparing different subsets: Entailed, Non-entailed, Combined...${NC}\n"

python scripts/bootstrap_confidence_intervals.py \
  --input-file output/Accuracy_Report.csv \
  --output-dir bootstrap_results \
  --n-iterations 10000 \
  --ci 0.95 \
  --report-name Accuracy

echo -e "${GREEN}✓ Full analysis complete${NC}"

# Summary
echo -e "\n${BLUE}========================================${NC}"
echo -e "${GREEN}Analysis Complete!${NC}"
echo -e "${BLUE}========================================${NC}\n"

echo -e "${YELLOW}Results generated:${NC}"
echo "  • bootstrap_results_MC/Accuracy_MC_bootstrap_summary.txt"
echo "  • bootstrap_results_MC/Accuracy_MC_bootstrap_results.json"
echo "  • bootstrap_results/Accuracy_bootstrap_summary.txt"
echo "  • bootstrap_results/Accuracy_bootstrap_results.json"
echo ""
echo -e "${YELLOW}View the results:${NC}"
echo "  cat bootstrap_results_MC/Accuracy_MC_bootstrap_summary.txt"
echo "  cat bootstrap_results/Accuracy_bootstrap_summary.txt"
echo ""
echo -e "${YELLOW}Reference guide:${NC}"
echo "  cat BOOTSTRAP_GUIDE.md"
echo ""
