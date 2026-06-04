// com/example/util/URIUtils.java
package com.example.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for URI handling and task ID generation
 */
public class URIUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(URIUtils.class);

    // Base URIs for different namespaces
    private static final String RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS_NAMESPACE = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String OWL_NAMESPACE = "http://www.w3.org/2002/07/owl#";
    private static final String GENEALOGY_NAMESPACE = "http://www.example.com/genealogy.owl#";

    /**
     * Convert short form back to full URI for SPARQL queries
     */
    public static String getFullURI(String shortForm) {
        if (shortForm == null || shortForm.isEmpty()) {
            return shortForm;
        }

        // Handle standard RDF/RDFS/OWL properties
        if ("rdf:type".equals(shortForm)) {
            return RDF_NAMESPACE + "type";
        }
        if (shortForm.startsWith("rdfs:")) {
            return RDFS_NAMESPACE + shortForm.substring(5);
        }
        if (shortForm.startsWith("owl:")) {
            return OWL_NAMESPACE + shortForm.substring(4);
        }
        if (shortForm.startsWith("rdf:")) {
            return RDF_NAMESPACE + shortForm.substring(4);
        }

        // For genealogy ontology entities, reconstruct the full URI
        return GENEALOGY_NAMESPACE + shortForm;
    }

    /**
     * Generate standardized task ID for queries
     */
    public static String generateTaskId(String rootEntity, String subject, String predicate, String answerType) {
        if (rootEntity == null) rootEntity = "Thing";
        if (subject == null) subject = "unknown";
        if (predicate == null) predicate = "unknown";
        if (answerType == null) answerType = "BIN";

        return String.format("1hop-%s_%s-%s-%s-%s",
                rootEntity, subject, subject, predicate, answerType);
    }

    /**
     * Extract namespace from full URI
     */
    public static String getNamespace(String fullURI) {
        if (fullURI == null) return "";

        int hashIndex = fullURI.lastIndexOf('#');
        int slashIndex = fullURI.lastIndexOf('/');

        int splitIndex = Math.max(hashIndex, slashIndex);
        return splitIndex > 0 ? fullURI.substring(0, splitIndex + 1) : fullURI;
    }

    /**
     * Extract local name from full URI
     */
    public static String getLocalName(String fullURI) {
        if (fullURI == null) return "";

        int hashIndex = fullURI.lastIndexOf('#');
        int slashIndex = fullURI.lastIndexOf('/');

        int splitIndex = Math.max(hashIndex, slashIndex);
        return splitIndex >= 0 && splitIndex < fullURI.length() - 1 ?
                fullURI.substring(splitIndex + 1) : fullURI;
    }
}