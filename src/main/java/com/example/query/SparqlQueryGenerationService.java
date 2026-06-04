
package com.example.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for generating SPARQL queries from ontology inferences
 */
@Service
public class SparqlQueryGenerationService implements QueryGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SparqlQueryGenerationService.class);

    @Override
    public String generateQuery(String subject, String predicate, String object) {
        // Generate ASK query
        StringBuilder query = new StringBuilder();
        query.append("ASK { ");

        if (subject != null && !subject.isEmpty()) {
            query.append("<").append(subject).append("> ");
        } else {
            query.append("?subject ");
        }

        if (predicate != null && !predicate.isEmpty()) {
            if (predicate.equals("rdf:type")) {
                query.append("a ");
            } else {
                query.append("<").append(predicate).append("> ");
            }
        } else {
            query.append("?predicate ");
        }

        if (object != null && !object.isEmpty()) {
            if (object.startsWith("http://") || object.startsWith("https://")) {
                query.append("<").append(object).append("> ");
            } else {
                query.append("\"").append(object).append("\" ");
            }
        } else {
            query.append("?object ");
        }

        query.append("}");

        LOGGER.debug("Generated ASK query: {}", query.toString());
        return query.toString();
    }

    @Override
    public String generateSelectQuery(String subject, String predicate, String object) {
        StringBuilder query = new StringBuilder();
        query.append("SELECT ");

        // Determine what to select based on null parameters
        if (subject == null || subject.isEmpty()) {
            query.append("?subject ");
        }
        if (predicate == null || predicate.isEmpty()) {
            query.append("?predicate ");
        }
        if (object == null || object.isEmpty()) {
            query.append("?object ");
        }

        query.append("WHERE { ");

        if (subject != null && !subject.isEmpty()) {
            query.append("<").append(subject).append("> ");
        } else {
            query.append("?subject ");
        }

        if (predicate != null && !predicate.isEmpty()) {
            if (predicate.equals("rdf:type")) {
                query.append("a ");
            } else {
                query.append("<").append(predicate).append("> ");
            }
        } else {
            query.append("?predicate ");
        }

        if (object != null && !object.isEmpty()) {
            if (object.startsWith("http://") || object.startsWith("https://")) {
                query.append("<").append(object).append("> ");
            } else {
                query.append("\"").append(object).append("\" ");
            }
        } else {
            query.append("?object ");
        }

        query.append("}");

        LOGGER.debug("Generated SELECT query: {}", query.toString());
        return query.toString();
    }

    /**
     * Generate a query for class assertion
     */
    public String generateClassAssertionQuery(String individual, String className) {
        return generateQuery(individual, "rdf:type", className);
    }

    /**
     * Generate a query for property assertion
     */
    public String generatePropertyAssertionQuery(String subject, String property, String object) {
        return generateQuery(subject, property, object);
    }

    /**
     * Generate a query to find all instances of a class
     */
    public String generateInstancesQuery(String className) {
        return generateSelectQuery(null, "rdf:type", className);
    }

    /**
     * Generate a query to find all properties of an individual
     */
    public String generatePropertiesQuery(String individual) {
        return generateSelectQuery(individual, null, null);
    }
}