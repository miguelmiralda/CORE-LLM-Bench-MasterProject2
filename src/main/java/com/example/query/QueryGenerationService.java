package com.example.query;

/**
 * Interface for generating SPARQL queries
 */
public interface QueryGenerationService {

    /**
     * Generate an ASK query
     */
    String generateQuery(String subject, String predicate, String object);

    /**
     * Generate a SELECT query
     */
    String generateSelectQuery(String subject, String predicate, String object);
}