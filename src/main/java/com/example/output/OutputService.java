// com/example/output/OutputService.java
package com.example.output;

import java.io.IOException;
import java.util.List;

public interface OutputService {
    void initialize() throws IOException;

    void writeQueryWithTags(String taskId, String query, String taskType, String answer, String explanation, String tags);

    void writeComprehensiveQuery(String taskId, String rootEntity, int tboxSize, int aboxSize,
                                 String taskType, String answerType, String sparqlQuery,
                                 String predicate, String answer, List<String> allAnswers,
                                 int minTagLength, int maxTagLength, int c1AxiomTypes,
                                 int c7ModalDepth, int c8SignatureDifference,
                                 int c9AxiomTypeDiff, int justificationComplexityScore);

    void writeExplanationWithTags(String key, String explanation, String tags);

    void writeExplanationWithComprehensiveFormat(String key, String comprehensiveExplanation);

    void setTotalQueries(long total);
    void logProgress(String operation, long completed, long total);
    void flush();
    void close() throws IOException;
}