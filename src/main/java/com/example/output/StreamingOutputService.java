// com/example/output/StreamingOutputService.java
package com.example.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.nio.file.Files;


/**
 * Enhanced streaming output service with comprehensive CSV format and exact JSON structure
 */
public class StreamingOutputService implements OutputService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingOutputService.class);

    private final String outputDirectory;
    private final AtomicLong queryCounter = new AtomicLong(0);
    private final AtomicLong explanationCounter = new AtomicLong(0);
    private final ObjectMapper objectMapper = new ObjectMapper();  // ADD THIS FIELD

    private FileWriter queryWriter;
    private FileWriter explanationWriter;
    private long totalQueries = 0;
    private boolean isFirstExplanation = true;

    public StreamingOutputService(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    @Override
    public void initialize() throws IOException {
        LOGGER.info("Initializing StreamingOutputService with output directory: {}", outputDirectory);

        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // FIXED: Append mode for CSV
        File queryFile = new File(outputDir, "SPARQL_questions.csv");
        boolean csvExists = queryFile.exists() && queryFile.length() > 0;
        queryWriter = new FileWriter(queryFile, StandardCharsets.UTF_8, true); // APPEND mode

        if (!csvExists) {
            // Add header only for new file
            queryWriter.write(
    "\"Task ID\",\"Root Entity\",\"TBox Size\",\"ABox Size\"," +
    "\"Task Type\",\"Answer Type\",\"SPARQL Query\"," +
    "\"NL Question (Direct)\",\"NL Question (Contextual)\"," +
    "\"NL Question (Relational)\",\"NL Question (Formal)\"," +
    "\"Complexity (Direct)\",\"Complexity (Contextual)\"," +
    "\"Complexity (Relational)\",\"Complexity (Formal)\"," +
    "\"LLM Answer (Direct)\",\"LLM Answer (Contextual)\"," +
    "\"LLM Answer (Relational)\",\"LLM Answer (Formal)\"," +
    "\"Correct (Direct)\",\"Correct (Contextual)\"," +
    "\"Correct (Relational)\",\"Correct (Formal)\"," +
    "\"Predicate\",\"Ground Truth Answer\"," +
    "\"Min Tag Length\",\"Max Tag Length\"\n");}

        // FIXED: Append mode for JSON
        File explanationFile = new File(outputDir, "Explanations.json");
        boolean jsonExists = explanationFile.exists() && explanationFile.length() > 0;
        explanationWriter = new FileWriter(explanationFile, StandardCharsets.UTF_8, true); // APPEND mode

        if (!jsonExists) {
            // Start JSON structure for new file
            explanationWriter.write("{\n");
            isFirstExplanation = true;
        } else {
            // For existing file, remove the closing "}" and continue
            removeLastCharacterFromFile(explanationFile);
            explanationWriter.write(",\n"); // Add comma to continue JSON
            isFirstExplanation = false;
        }

        LOGGER.info("Output files initialized (append mode): queries={}, explanations={}",
                queryFile.getPath(), explanationFile.getPath());
    }

    // Helper method to remove closing brace from JSON file
    private void removeLastCharacterFromFile(File file) {
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (content.trim().endsWith("}")) {
                content = content.trim();
                content = content.substring(0, content.length() - 1).trim(); // Remove last "}"
                Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not modify existing JSON file: {}", e.getMessage());
        }
    }

    @Override
    public void writeQueryWithTags(String taskId, String query, String taskType, String answer, String explanation, String tags) {
        // Legacy method - redirect to comprehensive format with default tag lengths
        try {writeComprehensiveQuery(taskId, "Thing", 100, 50, taskType, "BIN", query,
    "", "", "", "",          // verbalisations
    "", "", "", "",          // LLM answers
    "predicate", answer, null, 1, 1);
        } catch (Exception e) {
            LOGGER.error("Error in legacy writeQueryWithTags: {}", e.getMessage());
        }
    }

    @Override
    public void writeExplanationWithTags(String key, String explanation, String tags) {
        try {
            long currentCount = explanationCounter.incrementAndGet();

            ObjectNode jsonNode = objectMapper.createObjectNode();
            jsonNode.put("explanation", explanation);
            jsonNode.put("tags", tags);
            jsonNode.put("timestamp", System.currentTimeMillis());

            String jsonString = objectMapper.writeValueAsString(jsonNode);

            synchronized (explanationWriter) {
                if (!isFirstExplanation) {
                    explanationWriter.write(",\n");
                }
                explanationWriter.write("\"" + escapeJSON(key) + "\": " + jsonString);
                explanationWriter.flush();
                isFirstExplanation = false;
            }

            if (currentCount % 1000 == 0) {
                logProgress("explanations", currentCount, 0);
            }

        } catch (IOException e) {
            LOGGER.error("Error writing explanation with tags: {}", key, e);
        }
    }

@Override
public void writeComprehensiveQuery(String taskId, String rootEntity, int tboxSize, int aboxSize,
                                    String taskType, String answerType, String sparqlQuery,
                                    String verbDirect, String verbContextual,
                                    String verbRelational, String verbFormal,
                                    String llmDirect, String llmContextual,
                                    String llmRelational, String llmFormal,
                                    String predicate, String answer, List<String> allAnswers,
                                    int minTagLength, int maxTagLength) { // Updated parameters
        try {
            long currentCount = queryCounter.incrementAndGet();

            // For multi-choice queries, include all possible answers in the Answer column
            String finalAnswer = answer;
if ("MC".equals(answerType) && allAnswers != null && !allAnswers.isEmpty()) {
    finalAnswer = String.join("; ", allAnswers);
}

// Complexity = word count of each question
int cxDirect     = wordCount(verbDirect);
int cxContextual = wordCount(verbContextual);
int cxRelational = wordCount(verbRelational);
int cxFormal     = wordCount(verbFormal);

// Correctness flags
String corDirect     = isCorrect(llmDirect,     finalAnswer, answerType) ? "1" : "0";
String corContextual = isCorrect(llmContextual, finalAnswer, answerType) ? "1" : "0";
String corRelational = isCorrect(llmRelational, finalAnswer, answerType) ? "1" : "0";
String corFormal     = isCorrect(llmFormal,     finalAnswer, answerType) ? "1" : "0";

String csvLine = String.format(
    "\"%s\",\"%s\",%d,%d,\"%s\",\"%s\",\"%s\"," +
    "\"%s\",\"%s\",\"%s\",\"%s\"," +
    "%d,%d,%d,%d," +
    "\"%s\",\"%s\",\"%s\",\"%s\"," +
    "%s,%s,%s,%s," +
    "\"%s\",\"%s\",%d,%d\n",
    escapeCSV(taskId), escapeCSV(rootEntity), tboxSize, aboxSize,
    escapeCSV(taskType), escapeCSV(answerType), escapeCSV(sparqlQuery),
    escapeCSV(verbDirect), escapeCSV(verbContextual),
    escapeCSV(verbRelational), escapeCSV(verbFormal),
    cxDirect, cxContextual, cxRelational, cxFormal,
    escapeCSV(llmDirect), escapeCSV(llmContextual),
    escapeCSV(llmRelational), escapeCSV(llmFormal),
    corDirect, corContextual, corRelational, corFormal,
    escapeCSV(predicate), escapeCSV(finalAnswer),
    minTagLength, maxTagLength);
            synchronized (queryWriter) {
                queryWriter.write(csvLine);
                queryWriter.flush();
            }

            if (currentCount % 1000 == 0) {
                logProgress("queries", currentCount, totalQueries);
            }

        } catch (IOException e) {
            LOGGER.error("Error writing comprehensive query: {}", taskId, e);
        }
    }

    @Override
    public void writeExplanationWithComprehensiveFormat(String key, String comprehensiveExplanation) {
        try {
            long currentCount = explanationCounter.incrementAndGet();

            synchronized (explanationWriter) {
                if (!isFirstExplanation) {
                    explanationWriter.write(",\n");
                }
                explanationWriter.write(comprehensiveExplanation);
                explanationWriter.flush();
                isFirstExplanation = false;
            }

            if (currentCount % 1000 == 0) {
                logProgress("explanations", currentCount, 0);
            }

        } catch (IOException e) {
            LOGGER.error("Error writing comprehensive explanation: {}", key, e);
        }
    }

    private String escapeJSON(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeCSV(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ");
    }
    private int wordCount(String text) {
    if (text == null || text.isBlank()) return 0;
    return text.trim().split("\\s+").length;
}

private boolean isCorrect(String llmAnswer, String groundTruth, String answerType) {
    if (llmAnswer == null || llmAnswer.isBlank()) return false;
    String llm   = llmAnswer.trim().toUpperCase();
    String truth = (groundTruth == null) ? "" : groundTruth.trim().toUpperCase();
    if ("UNKNOWN".equals(llm) || "ERROR".equals(llm)) return false;
    if ("BIN".equals(answerType)) return llm.equals(truth);
    // MC: partial match — at least one value overlaps
    for (String t : truth.split("[;,]")) {
        if (llm.contains(t.trim().toUpperCase())) return true;
    }
    return false;
}
    @Override
    public void setTotalQueries(long total) {
        this.totalQueries = total;
        LOGGER.info("Expected total queries: {}", total);
    }

    @Override
    public void logProgress(String operation, long completed, long total) {
        if (total > 0) {
            double percentage = (completed * 100.0) / total;
            LOGGER.info("Progress {}: {}/{} ({:.1f}%)", operation, completed, total, percentage);
        } else {
            LOGGER.info("Progress {}: {} completed", operation, completed);
        }
    }

    @Override
    public void flush() {
        try {
            if (queryWriter != null) {
                queryWriter.flush();
            }
            if (explanationWriter != null) {
                explanationWriter.flush();
            }
        } catch (IOException e) {
            LOGGER.error("Error flushing output", e);
        }
    }

    @Override
    public void close() throws IOException {
        LOGGER.info("Closing StreamingOutputService. Final counts: queries={}, explanations={}",
                queryCounter.get(), explanationCounter.get());

        if (queryWriter != null) {
            try {
                queryWriter.close();
            } catch (IOException e) {
                LOGGER.error("Error closing query writer", e);
            }
        }

        if (explanationWriter != null) {
            try {
                explanationWriter.write("\n}");
                explanationWriter.close();
            } catch (IOException e) {
                LOGGER.error("Error closing explanation writer", e);
            }
        }
    }
}