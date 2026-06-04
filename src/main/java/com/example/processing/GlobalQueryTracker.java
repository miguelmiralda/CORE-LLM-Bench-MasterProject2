// com/example/processing/GlobalQueryTracker.java
package com.example.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks queries across all ontologies to prevent duplicates and manage task ID references
 */
public class GlobalQueryTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalQueryTracker.class);

    // Thread-safe collections for concurrent processing if needed
    private static final Set<String> processedQueries = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<String>> queryToTaskIds = new ConcurrentHashMap<>();
    private static final Map<String, String> queryToFirstOntology = new ConcurrentHashMap<>();

    /**
     * Check if a query should be processed (first time seeing this triple)
     */
    public static synchronized boolean shouldProcessQuery(String tripleKey) {
        return !processedQueries.contains(tripleKey);
    }

    /**
     * Mark a query as processed and store the first ontology that contained it
     */
    public static synchronized boolean markQueryProcessed(String tripleKey, String ontologyName) {
        boolean isFirst = processedQueries.add(tripleKey);
        if (isFirst) {
            queryToFirstOntology.put(tripleKey, ontologyName);
            queryToTaskIds.put(tripleKey, new ArrayList<>());
            LOGGER.debug("First occurrence of query '{}' in ontology '{}'", tripleKey, ontologyName);
        } else {
            LOGGER.debug("Skipping duplicate query '{}' from ontology '{}' (first seen in '{}')",
                    tripleKey, ontologyName, queryToFirstOntology.get(tripleKey));
        }
        return isFirst;
    }

    /**
     * Add a task ID for a specific query
     */
    public static synchronized void addTaskId(String tripleKey, String taskId) {
        queryToTaskIds.computeIfAbsent(tripleKey, k -> new ArrayList<>()).add(taskId);
        LOGGER.debug("Added task ID '{}' for query '{}'", taskId, tripleKey);
    }

    /**
     * Get all task IDs associated with a query
     */
    public static synchronized List<String> getTaskIds(String tripleKey) {
        return new ArrayList<>(queryToTaskIds.getOrDefault(tripleKey, Collections.emptyList()));
    }

    /**
     * Check if a query has been processed
     */
    public static synchronized boolean isQueryProcessed(String tripleKey) {
        return processedQueries.contains(tripleKey);
    }

    /**
     * Get the name of the first ontology that contained this query
     */
    public static synchronized String getFirstOntology(String tripleKey) {
        return queryToFirstOntology.get(tripleKey);
    }

    /**
     * Get statistics about processed queries
     */
    public static synchronized QueryStats getStats() {
        int totalQueries = processedQueries.size();
        int totalTaskIds = queryToTaskIds.values().stream().mapToInt(List::size).sum();

        Map<String, Integer> ontologyDistribution = new HashMap<>();
        for (String ontology : queryToFirstOntology.values()) {
            ontologyDistribution.merge(ontology, 1, Integer::sum);
        }

        return new QueryStats(totalQueries, totalTaskIds, ontologyDistribution);
    }

    /**
     * Clear all tracking data (useful for testing or restarting)
     */
    public static synchronized void clear() {
        processedQueries.clear();
        queryToTaskIds.clear();
        queryToFirstOntology.clear();
        LOGGER.info("Cleared all global query tracking data");
    }

    /**
     * Get all processed queries (for debugging)
     */
    public static synchronized Set<String> getAllProcessedQueries() {
        return new HashSet<>(processedQueries);
    }

    /**
     * Statistics about processed queries
     */
    public static class QueryStats {
        private final int totalQueries;
        private final int totalTaskIds;
        private final Map<String, Integer> ontologyDistribution;

        public QueryStats(int totalQueries, int totalTaskIds, Map<String, Integer> ontologyDistribution) {
            this.totalQueries = totalQueries;
            this.totalTaskIds = totalTaskIds;
            this.ontologyDistribution = new HashMap<>(ontologyDistribution);
        }

        public int getTotalQueries() { return totalQueries; }
        public int getTotalTaskIds() { return totalTaskIds; }
        public Map<String, Integer> getOntologyDistribution() { return new HashMap<>(ontologyDistribution); }

        @Override
        public String toString() {
            return String.format("QueryStats{totalQueries=%d, totalTaskIds=%d, ontologies=%d}",
                    totalQueries, totalTaskIds, ontologyDistribution.size());
        }
    }
}