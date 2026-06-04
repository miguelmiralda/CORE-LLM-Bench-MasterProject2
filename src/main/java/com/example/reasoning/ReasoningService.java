package com.example.reasoning;

import openllet.owlapi.OpenlletReasoner;
import org.semanticweb.owlapi.model.*;

public interface ReasoningService extends AutoCloseable {

    /**
     * Initialize the reasoner with an ontology
     */
    void initializeReasoner(OWLOntology ontology);

    /**
     * Get the underlying Pellet reasoner
     */
    OpenlletReasoner getReasoner();

    /**
     * Check if the ontology is consistent
     */
    boolean isConsistent();

    /**
     * Precompute inferences for better performance
     */
    void precomputeInferences();

    /**
     * Check if the reasoner entails the given axiom
     */
    boolean isEntailed(OWLAxiom axiom);
}
