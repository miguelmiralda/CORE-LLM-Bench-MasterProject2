package com.example.evaluation;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class LlmEvaluationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmEvaluationService.class);

    @Value("${llm.api.url:}")
    private String apiUrl;

    @Value("${llm.api.key:}")
    private String apiKey;

    @Value("${llm.model:gpt-4.1-mini-2025-04-14}")
    private String model;

    @Value("${llm.mock.mode:true}")
    private boolean configuredMockMode;

    private boolean mockMode;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

   @PostConstruct
public void init() {
    this.mockMode = configuredMockMode
            || apiUrl == null || apiUrl.isBlank()
            || apiKey == null || apiKey.isBlank();

    if (mockMode) {
        LOGGER.warn("LlmEvaluationService running in MOCK mode — apiUrl={}", apiUrl);
    } else {
        LOGGER.info("LlmEvaluationService REAL mode, model={}", model);
    }
}
    public String askLlm(String question, String answerType) {
        if (mockMode || apiUrl == null || apiUrl.isBlank()) {
            return mockAnswer(question, answerType);
        }

        try {
            String prompt = buildPrompt(question, answerType);

            String requestBody = "{"
                    + "\"model\":\"" + escapeJson(model) + "\","
                    + "\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"You are a precise evaluator. Answer only in the required format.\"},"
                    + "{\"role\":\"user\",\"content\":\"" + escapeJson(prompt) + "\"}"
                    + "],"
                    + "\"temperature\":0"
                    + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.error("OpenAI API error: status={}, body={}", response.statusCode(), response.body());
                return "UNKNOWN";
            }

            LOGGER.debug("OpenAI raw response: {}", response.body());
            String rawText = extractContentFromChatCompletions(response.body());
            LOGGER.debug("Extracted content: '{}', normalized for {}: '{}'", rawText, answerType, normalizeAnswer(rawText, answerType));
            return normalizeAnswer(rawText, answerType);

        } catch (Exception e) {
            LOGGER.error("Error calling LLM API", e);
            return "UNKNOWN";
        }
    }

    private String buildPrompt(String question, String answerType) {
        if ("BIN".equalsIgnoreCase(answerType)) {
            return question + "\n\nReply with exactly one word: TRUE or FALSE.";
        } else if ("MC".equalsIgnoreCase(answerType)) {
            return question + "\n\nReply with a comma-separated list of all correct values, using the exact names as they appear in the question. Do not add any explanation.";
        }
        return question + "\n\nReply with only the final answer.";
    }

    private String normalizeAnswer(String rawText, String answerType) {
        if (rawText == null) return "UNKNOWN";

        String cleaned = rawText.trim().toUpperCase();

        if ("BIN".equalsIgnoreCase(answerType)) {
            if (cleaned.contains("TRUE")) return "TRUE";
            if (cleaned.contains("FALSE")) return "FALSE";
            return "UNKNOWN";
        }

        if ("MC".equalsIgnoreCase(answerType)) {
            if (cleaned.isBlank()) return "UNKNOWN";
            return cleaned;
        }

        return cleaned;
    }

    private String extractContentFromChatCompletions(String responseBody) {
        // Match "content": "..." with optional whitespace around the colon
        int idx = responseBody.indexOf("\"content\"");
        if (idx < 0) return "";
        int colon = responseBody.indexOf(':', idx + 9);
        if (colon < 0) return "";
        int quote = responseBody.indexOf('"', colon + 1);
        if (quote < 0) return "";
        int start = quote + 1;

        StringBuilder sb = new StringBuilder();
        boolean escape = false;

        for (int i = start; i < responseBody.length(); i++) {
            char ch = responseBody.charAt(i);

            if (escape) {
                switch (ch) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(ch);
                }
                escape = false;
            } else if (ch == '\\') {
                escape = true;
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }

        return sb.toString();
    }

    private String mockAnswer(String question, String answerType) {
        if ("BIN".equalsIgnoreCase(answerType)) {
            return "TRUE";
        }
        if ("MC".equalsIgnoreCase(answerType)) {
            return "A";
        }
        return "UNKNOWN";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}