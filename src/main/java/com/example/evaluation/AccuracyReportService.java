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
 * Tracks for each (strategy, subset) where subset = ENTAILED | NON_ENTAILED:
 *   - Total questions asked
 *   - Correct answers
 *   - Abstentions (LLM answered UNKNOWN)
 *   - Average question complexity (word count)
 */
@Service
public class AccuracyReportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccuracyReportService.class);

    public enum Subset { ENTAILED, NON_ENTAILED }

    // Key = "STRATEGY|SUBSET" e.g. "DIRECT|ENTAILED"
    private final Map<String, StrategyStats> stats = new ConcurrentHashMap<>();

    public AccuracyReportService() {
        for (String s : new String[]{"DIRECT", "CONTEXTUAL", "RELATIONAL", "FORMAL"}) {
            for (Subset sub : Subset.values()) {
                stats.put(key(s, sub), new StrategyStats(s, sub));
            }
        }
    }

    private static String key(String strategy, Subset subset) {
        return strategy + "|" + subset.name();
    }

    // ── Recording results ─────────────────────────────────────────────────

    /**
     * Record one evaluation result.
     *
     * @param strategy       e.g. "DIRECT"
     * @param subset         ENTAILED or NON_ENTAILED
     * @param groundTruth    correct answer ("TRUE" / "FALSE" / value)
     * @param llmAnswer      LLM's raw answer string
     * @param answerType     "BIN" or "MC"
     * @param questionLength word count of the verbalised question
     */
    public void record(String strategy, Subset subset, String groundTruth,
                       String llmAnswer, String answerType, int questionLength) {

        StrategyStats s = stats.get(key(strategy, subset));
        if (s == null) {
            LOGGER.warn("Unknown strategy/subset: {}/{}", strategy, subset);
            return;
        }

        s.total.incrementAndGet();
        s.totalComplexity.addAndGet(questionLength);

        String normalizedLlm   = normalize(llmAnswer);
        String normalizedTruth = normalize(groundTruth);

        if ("UNKNOWN".equals(normalizedLlm) || "ERROR".equals(normalizedLlm)) {
            s.abstentions.incrementAndGet();
        } else if (isCorrect(normalizedLlm, normalizedTruth, answerType)) {
            s.correct.incrementAndGet();
        }
    }

    // ── Accuracy computation ──────────────────────────────────────────────

    public double getAccuracy(String strategy, Subset subset) {
        StrategyStats s = stats.get(key(strategy, subset));
        if (s == null) return 0.0;
        int attempted = s.total.get() - s.abstentions.get();
        if (attempted == 0) return 0.0;
        return (double) s.correct.get() / attempted;
    }

    public double getAbstentionRate(String strategy, Subset subset) {
        StrategyStats s = stats.get(key(strategy, subset));
        if (s == null) return 0.0;
        int total = s.total.get();
        if (total == 0) return 0.0;
        return (double) s.abstentions.get() / total;
    }

    public double getAvgComplexity(String strategy, Subset subset) {
        StrategyStats s = stats.get(key(strategy, subset));
        if (s == null) return 0.0;
        int total = s.total.get();
        if (total == 0) return 0.0;
        return (double) s.totalComplexity.get() / total;
    }

    // Combined (entailed + non-entailed merged)
    private StrategyStats getCombined(String strategy) {
        StrategyStats e = stats.get(key(strategy, Subset.ENTAILED));
        StrategyStats n = stats.get(key(strategy, Subset.NON_ENTAILED));
        StrategyStats combined = new StrategyStats(strategy, null);
        combined.total.set(e.total.get() + n.total.get());
        combined.correct.set(e.correct.get() + n.correct.get());
        combined.abstentions.set(e.abstentions.get() + n.abstentions.get());
        combined.totalComplexity.set(e.totalComplexity.get() + n.totalComplexity.get());
        return combined;
    }

    private double combinedAccuracy(String strategy) {
        StrategyStats c = getCombined(strategy);
        int attempted = c.total.get() - c.abstentions.get();
        if (attempted == 0) return 0.0;
        return (double) c.correct.get() / attempted;
    }

    private double combinedAbstentionRate(String strategy) {
        StrategyStats c = getCombined(strategy);
        if (c.total.get() == 0) return 0.0;
        return (double) c.abstentions.get() / c.total.get();
    }

    private double combinedAvgComplexity(String strategy) {
        StrategyStats c = getCombined(strategy);
        if (c.total.get() == 0) return 0.0;
        return (double) c.totalComplexity.get() / c.total.get();
    }

    // ── Report writing ────────────────────────────────────────────────────

    public void writeReport(String outputDirectory) throws IOException {
        File dir = new File(outputDirectory);
        if (!dir.exists()) dir.mkdirs();

        File reportFile = new File(dir, "Accuracy_Report.csv");
        LOGGER.info("Writing accuracy report to: {}", reportFile.getAbsolutePath());

        try (FileWriter w = new FileWriter(reportFile, StandardCharsets.UTF_8)) {
            w.write("\"Subset\"," +
                    "\"Translation Strategy\"," +
                    "\"Total Questions\"," +
                    "\"Correct\"," +
                    "\"Abstentions (UNKNOWN)\"," +
                    "\"Wrong\"," +
                    "\"Accuracy (%)\"," +
                    "\"Abstention Rate (%)\"," +
                    "\"Avg Question Complexity (words)\"\n");

            String[] strategies = {"DIRECT", "CONTEXTUAL", "RELATIONAL", "FORMAL"};

            // Entailed
            for (String strat : strategies) {
                writeRow(w, "Entailed", strat, stats.get(key(strat, Subset.ENTAILED)));
            }
            // Non-entailed
            for (String strat : strategies) {
                writeRow(w, "Non-entailed", strat, stats.get(key(strat, Subset.NON_ENTAILED)));
            }
            // Combined
            for (String strat : strategies) {
                StrategyStats c = getCombined(strat);
                writeRow(w, "Combined", strat, c);
            }
        }

        LOGGER.info("Accuracy report written successfully.");
        printSummaryToLog();
    }

    private void writeRow(FileWriter w, String subset, String strategy, StrategyStats s) throws IOException {
        int total   = s.total.get();
        int correct = s.correct.get();
        int abstain = s.abstentions.get();
        int wrong   = total - correct - abstain;
        int attempted = total - abstain;
        double acc     = attempted == 0 ? 0.0 : (double) correct / attempted * 100.0;
        double absRate = total == 0 ? 0.0 : (double) abstain / total * 100.0;
        double complex = total == 0 ? 0.0 : (double) s.totalComplexity.get() / total;

        w.write(String.format("\"%s\",\"%s\",%d,%d,%d,%d,\"%.2f\",\"%.2f\",\"%.2f\"\n",
                subset, strategy, total, correct, abstain, wrong, acc, absRate, complex));
    }

    private void printSummaryToLog() {
        LOGGER.info("=== ACCURACY SUMMARY ===");
        for (Subset sub : Subset.values()) {
            LOGGER.info("-- {} --", sub.name());
            for (String name : new String[]{"DIRECT", "CONTEXTUAL", "RELATIONAL", "FORMAL"}) {
                LOGGER.info("[{}] Accuracy={}%  Abstention={}%  AvgComplexity={} words",
                        name,
                        String.format("%.1f", getAccuracy(name, sub) * 100),
                        String.format("%.1f", getAbstentionRate(name, sub) * 100),
                        String.format("%.1f", getAvgComplexity(name, sub)));
            }
        }
        LOGGER.info("-- COMBINED --");
        for (String name : new String[]{"DIRECT", "CONTEXTUAL", "RELATIONAL", "FORMAL"}) {
            LOGGER.info("[{}] Accuracy={}%  Abstention={}%  AvgComplexity={} words",
                    name,
                    String.format("%.1f", combinedAccuracy(name) * 100),
                    String.format("%.1f", combinedAbstentionRate(name) * 100),
                    String.format("%.1f", combinedAvgComplexity(name)));
        }
    }

    // ── Correctness check ─────────────────────────────────────────────────

    private boolean isCorrect(String llmAnswer, String groundTruth, String answerType) {
        if ("BIN".equals(answerType)) {
            return llmAnswer.equals(groundTruth);
        } else {
            Set<String> truthSet = splitAnswerSet(groundTruth);
            Set<String> llmSet   = splitAnswerSet(llmAnswer);
            if (truthSet.isEmpty()) return false;
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
        final Subset subset;
        final AtomicInteger total           = new AtomicInteger(0);
        final AtomicInteger correct         = new AtomicInteger(0);
        final AtomicInteger abstentions     = new AtomicInteger(0);
        final AtomicInteger totalComplexity = new AtomicInteger(0);

        StrategyStats(String name, Subset subset) {
            this.name = name;
            this.subset = subset;
        }
    }
}
