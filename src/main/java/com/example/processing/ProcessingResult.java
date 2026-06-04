package com.example.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Result container for processing operations
 */
public class ProcessingResult {
    private final AtomicLong processedQueries = new AtomicLong(0);
    private final AtomicLong processedExplanations = new AtomicLong(0);
    private final AtomicLong processedTriples = new AtomicLong(0);

    // Add missing fields
    private final AtomicLong totalInferences = new AtomicLong(0);
    private final AtomicLong binaryQueries = new AtomicLong(0);
    private final AtomicLong multiChoiceQueries = new AtomicLong(0);
    private double memoryUsedMB = 0.0;

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean success = true;
    private String errorMessage;
    private long processingTimeMs;

    public ProcessingResult() {
        // Default constructor
    }

    // Existing methods...
    public long getProcessedQueries() {
        return processedQueries.get();
    }

    public void incrementProcessedQueries() {
        processedQueries.incrementAndGet();
    }

    public void addProcessedQueries(long count) {
        processedQueries.addAndGet(count);
    }

    public long getProcessedExplanations() {
        return processedExplanations.get();
    }

    public void incrementProcessedExplanations() {
        processedExplanations.incrementAndGet();
    }

    public void addProcessedExplanations(long count) {
        processedExplanations.addAndGet(count);
    }

    // Add missing methods that OwlSparqlGenerator expects
    public long getTotalInferences() {
        return totalInferences.get();
    }

    public void setTotalInferences(long count) {
        totalInferences.set(count);
    }

    public void incrementTotalInferences() {
        totalInferences.incrementAndGet();
    }

    public void addTotalInferences(long count) {
        totalInferences.addAndGet(count);
    }

    public long getBinaryQueries() {
        return binaryQueries.get();
    }

    public void setBinaryQueries(long count) {
        binaryQueries.set(count);
    }

    public void incrementBinaryQueries() {
        binaryQueries.incrementAndGet();
    }

    public void addBinaryQueries(long count) {
        binaryQueries.addAndGet(count);
    }

    public long getMultiChoiceQueries() {
        return multiChoiceQueries.get();
    }

    public void setMultiChoiceQueries(long count) {
        multiChoiceQueries.set(count);
    }

    public void incrementMultiChoiceQueries() {
        multiChoiceQueries.incrementAndGet();
    }

    public void addMultiChoiceQueries(long count) {
        multiChoiceQueries.addAndGet(count);
    }

    public double getMemoryUsedMB() {
        return memoryUsedMB;
    }

    public void setMemoryUsedMB(double memoryUsedMB) {
        this.memoryUsedMB = memoryUsedMB;
    }

    // Existing methods continue...
    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }

    public void addError(String error) {
        errors.add(error);
        success = false;
    }

    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public boolean isSuccess() {
        return success && errors.isEmpty();
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setError(String errorMessage) {
        this.errorMessage = errorMessage;
        this.success = false;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public void setProcessingTimeMs(long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public int getErrorCount() {
        return errors.size();
    }

    public int getWarningCount() {
        return warnings.size();
    }

    @Override
    public String toString() {
        return String.format("ProcessingResult{success=%s, queries=%d, explanations=%d, triples=%d, " +
                        "totalInferences=%d, binaryQueries=%d, multiChoiceQueries=%d, " +
                        "errors=%d, warnings=%d, timeMs=%d, memoryMB=%.2f}",
                success, processedQueries.get(), processedExplanations.get(), processedTriples.get(),
                totalInferences.get(), binaryQueries.get(), multiChoiceQueries.get(),
                errors.size(), warnings.size(), processingTimeMs, memoryUsedMB);
    }
}