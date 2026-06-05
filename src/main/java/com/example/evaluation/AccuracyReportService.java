package com.example.evaluation;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects per-strategy LLM evaluation results and writes a summary report.
 *
 * Tracks for each translation strategy:
 *   - Total questions asked
 *   - Correct answers (LLM answer matches ground truth)
 *   - Abstentions (LLM answered UNKNOWN)
 *   - Average question complexity (word count)
 *   - Accuracy = correct / (total - abstentions)
 *   - Abstention rate = unknowns / total
 */
@Service
public class AccuracyReportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccuracyReportService.class);

    // One bucket per strategy
    private final Map<String, StrategyStats> stats = new ConcurrentHashMap<>();

    public AccuracyReportService() {
        for (String s : new String[]{"DIRECT", "CONTEXTUAL", "RELATIONAL", "FORMAL"}) {
            stats.put(s, new StrategyStats(s));
        }
    }

    // ── Recording results ─────────────────────────────────────────────────

    /**
     * Record one evaluation result for a given strategy.
     *
     * @param strategy       e.g. "DIRECT"
     * @param groundTruth    the correct answer from the ontology (e.g. "TRUE" / "FALSE" / value)
     * @param llmAnswer      the LLM's raw answer string
     * @param answerType     "BIN" or "MC"
     * @param questionLength word count of the verbalised question (complexity proxy)
     */
    public void record(String strategy, String groundTruth,
                       String llmAnswer, String answerType, int questionLength) {

        StrategyStats s = stats.get(strategy);
        if (s == null) {
            LOGGER.warn("Unknown strategy: {}", strategy);
            return;
        }

        s.total.incrementAndGet();
        s.totalComplexity.addAndGet(questionLength);

        String normalizedLlm    = normalize(llmAnswer);
        String normalizedTruth  = normalize(groundTruth);

        if ("UNKNOWN".equals(normalizedLlm) || "ERROR".equals(normalizedLlm)) {
            s.abstentions.incrementAndGet();
        } else if (isCorrect(normalizedLlm, normalizedTruth, answerType)) {
            s.correct.incrementAndGet();
        }
        // else: wrong answer — counted in total, not in correct
    }

    // ── Accuracy computation ──────────────────────────────────────────────

    /** Returns accuracy for a strategy: correct / (total - abstentions) */
    public double getAccuracy(String strategy) {
        StrategyStats s = stats.get(strategy);
        if (s == null) return 0.0;
        int attempted = s.total.get() - s.abstentions.get();
        if (attempted == 0) return 0.0;
        return (double) s.correct.get() / attempted;
    }

    /** Returns abstention rate: UNKNOWN answers / total */
    public double getAbstentionRate(String strategy) {
        StrategyStats s = stats.get(strategy);
        if (s == null) return 0.0;
        int total = s.total.get();
        if (total == 0) return 0.0;
        return (double) s.abstentions.get() / total;
    }

    /** Returns average word count (complexity) of questions for a strategy */
    public double getAvgComplexity(String strategy) {
        StrategyStats s = stats.get(strategy);
        if (s == null) return 0.0;
        int total = s.total.get();
        if (total == 0) return 0.0;
        return (double) s.totalComplexity.get() / total;
    }

    // ── Report writing ────────────────────────────────────────────────────

    /**
     * Writes Accuracy_Report.csv summarising accuracy + complexity per strategy.
     *
     * @param outputDirectory directory where the file will be written
     */
    public void writeReport(String outputDirectory) throws IOException {
        File dir = new File(outputDirectory);
        if (!dir.exists()) dir.mkdirs();

        File reportFile = new File(dir, "Accuracy_Report.csv");
        LOGGER.info("Writing accuracy report to: {}", reportFile.getAbsolutePath());

        try (FileWriter w = new FileWriter(reportFile, StandardCharsets.UTF_8)) {
            // Header
            w.write("\"Translation Strategy\"," +
                    "\"Total Questions\"," +
                    "\"Correct\"," +
                    "\"Abstentions (UNKNOWN)\"," +
                    "\"Wrong\"," +
                    "\"Accuracy (%)\"," +
                    "\"Abstention Rate (%)\"," +
                    "\"Avg Question Complexity (words)\"\n");

            for (String strategyName : new String[]{"DIRECT", "CONTEXTUAL", "RELATIONAL", "FORMAL"}) {
                StrategyStats s = stats.get(strategyName);
                int total      = s.total.get();
                int correct    = s.correct.get();
                int abstain    = s.abstentions.get();
                int wrong      = total - correct - abstain;
                double acc     = getAccuracy(strategyName) * 100.0;
                double absRate = getAbstentionRate(strategyName) * 100.0;
                double complex = getAvgComplexity(strategyName);

                w.write(String.format("\"%s\",%d,%d,%d,%d,\"%.2f\",\"%.2f\",\"%.2f\"\n",
                        strategyName, total, correct, abstain, wrong,
                        acc, absRate, complex));
            }
        }

        LOGGER.info("Accuracy report written successfully.");
        printSummaryToLog();
    }

    private void printSummaryToLog() {
        LOGGER.info("=== ACCURACY SUMMARY ===");
        for (String name : new String[]{"DIRECT", "CONTEXTUAL", "RELATIONAL", "FORMAL"}) {
            LOGGER.info("[{}] Accuracy={}%  Abstention={}%  AvgComplexity={} words",
                    name,
                    String.format("%.1f", getAccuracy(name) * 100),
        String.format("%.1f", getAbstentionRate(name) * 100),
        String.format("%.1f", getAvgComplexity(name)));
        }
    }

    // ── Correctness check ─────────────────────────────────────────────────

    private boolean isCorrect(String llmAnswer, String groundTruth, String answerType) {
        if ("BIN".equals(answerType)) {
            // For BIN: direct string match (both already normalized to uppercase)
            return llmAnswer.equals(groundTruth);
        } else {
            // For MC: check if LLM answer contains at least one correct value
            // Ground truth may be "val1; val2; val3" — split and check overlap
            Set<String> truthSet = splitAnswerSet(groundTruth);
            Set<String> llmSet   = splitAnswerSet(llmAnswer);
            if (truthSet.isEmpty()) return false;
            // Partial credit: at least one correct value
            for (String t : truthSet) {
                if (llmSet.contains(t)) return true;
            }
            return false;
        }
    }

    private Set<String> splitAnswerSet(String answer) {
        Set<String> result = new HashSet<>();
        if (answer == null || answer.isBlank()) return result;
        for (String part : answer.split("[;,]")) {
            String trimmed = part.trim().toUpperCase();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private String normalize(String s) {
        if (s == null) return "UNKNOWN";
        return s.trim().toUpperCase();
    }

    // ── Internal stats holder ─────────────────────────────────────────────

    private static class StrategyStats {
        final String name;
        final AtomicInteger total      = new AtomicInteger(0);
        final AtomicInteger correct    = new AtomicInteger(0);
        final AtomicInteger abstentions = new AtomicInteger(0);
        final AtomicInteger totalComplexity = new AtomicInteger(0);

        StrategyStats(String name) { this.name = name; }
    }
}