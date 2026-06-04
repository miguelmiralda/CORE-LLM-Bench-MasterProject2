// Updated OntologyService.java
package com.example.ontology;

import org.semanticweb.owlapi.model.OWLOntology;

import java.io.File;
import java.util.List;

public interface OntologyService extends AutoCloseable {
    /**
     * Load all small ontologies from a directory
     */
    List<OWLOntology> loadOntologiesFromDirectory(String directoryPath);

    /**
     * Load a single ontology from file
     */
    OWLOntology loadOntology(File ontologyFile);

    /**
     * Get ontology statistics
     */
    OntologyStats getStats(OWLOntology ontology);

    /**
     * Get combined statistics for multiple ontologies
     */
    OntologyStats getCombinedStats(List<OWLOntology> ontologies);
}