
package com.example.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Performance tracking utility for measuring execution times
 */
public class PerformanceTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceTracker.class);

    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> durations = new ConcurrentHashMap<>();

    /**
     * Start tracking time for a given operation
     */
    public void start(String operationName) {
        startTimes.put(operationName, System.currentTimeMillis());
        LOGGER.debug("Started timing operation: {}", operationName);
    }

    /**
     * End tracking time for a given operation
     */
    public void end(String operationName) {
        Long startTime = startTimes.get(operationName);
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            durations.put(operationName, duration);
            LOGGER.debug("Completed operation '{}' in {} ms", operationName, duration);
            startTimes.remove(operationName);
        } else {
            LOGGER.warn("No start time found for operation: {}", operationName);
        }
    }

    /**
     * Get duration of a completed operation
     */
    public long getDuration(String operationName) {
        return durations.getOrDefault(operationName, 0L);
    }

    /**
     * Log summary of all tracked operations
     */
    public void logSummary() {
        LOGGER.info("=== Performance Summary ===");
        durations.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry ->
                        LOGGER.info("{}: {} ms", entry.getKey(), entry.getValue())
                );

        long totalTime = durations.values().stream().mapToLong(Long::longValue).sum();
        LOGGER.info("Total tracked time: {} ms", totalTime);
    }

    /**
     * Clear all tracking data
     */
    public void clear() {
        startTimes.clear();
        durations.clear();
    }
}