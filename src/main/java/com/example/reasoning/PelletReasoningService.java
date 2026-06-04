package com.example.reasoning;

import openllet.owlapi.OpenlletReasoner;
import openllet.owlapi.OpenlletReasonerFactory;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

import java.util.Set;
import java.util.Collections;

/**
 * Pellet-based reasoning service implementation
 */
@Service
public class PelletReasoningService implements ReasoningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PelletReasoningService.class);

    private OpenlletReasoner reasoner;
    private OWLOntology ontology;

    @Override
    public void initializeReasoner(OWLOntology ontology) {
        this.ontology = ontology;
        OpenlletReasonerFactory factory = new OpenlletReasonerFactory();

        // Create configuration for better explanation support
        OWLReasonerConfiguration config = new SimpleConfiguration();
        this.reasoner = factory.createReasoner(ontology, config);

        // Enable explanation tracking
        reasoner.getKB().setDoExplanation(true);

        reasoner.prepareReasoner();
        LOGGER.info("Pellet reasoner initialized with explanation support");
    }


    @Override
    public void precomputeInferences() {
        if (reasoner == null) {
            LOGGER.warn("Cannot precompute inferences - reasoner not initialized");
            return;
        }

        LOGGER.info("Precomputing essential inferences only...");
        long startTime = System.currentTimeMillis();

        try {
            // Only precompute the most essential inferences to avoid memory issues
            reasoner.precomputeInferences(
                    InferenceType.CLASS_HIERARCHY,
                    InferenceType.CLASS_ASSERTIONS
            );

            long duration = System.currentTimeMillis() - startTime;
            LOGGER.info("Essential inference precomputation completed in {} ms", duration);

            // Add the rest incrementally if memory allows
            try {
                reasoner.precomputeInferences(
                        InferenceType.OBJECT_PROPERTY_HIERARCHY,
                        InferenceType.OBJECT_PROPERTY_ASSERTIONS
                );
                LOGGER.info("Additional inferences precomputed successfully");
            } catch (OutOfMemoryError e) {
                LOGGER.warn("Skipping additional inferences due to memory constraints");
            }

        } catch (OutOfMemoryError e) {
            LOGGER.error("Out of memory during inference precomputation - using on-demand reasoning", e);
            // Continue without precomputation
        } catch (Exception e) {
            LOGGER.error("Error during inference precomputation", e);
        }
    }

    // new method to check reasoner performance:
    public boolean isReasonerHealthy() {
        if (reasoner == null) return false;

        try {
            // Quick consistency check
            return reasoner.isConsistent();
        } catch (OutOfMemoryError e) {
            LOGGER.error("Reasoner out of memory", e);
            return false;
        } catch (Exception e) {
            LOGGER.warn("Reasoner health check failed", e);
            return false;
        }
    }

    @Override
    public OpenlletReasoner getReasoner() {
        if (reasoner == null) {
            throw new IllegalStateException("Reasoner not initialized. Call initializeReasoner() first.");
        }
        return reasoner;
    }

    @Override
    public boolean isConsistent() {
        if (reasoner == null) {
            return false;
        }

        boolean consistent = reasoner.isConsistent();
        LOGGER.info("Ontology consistency check: {}", consistent ? "CONSISTENT" : "INCONSISTENT");
        return consistent;
    }

    @Override
    public boolean isEntailed(OWLAxiom axiom) {
        if (reasoner == null) {
            return false;
        }

        try {
            return reasoner.isEntailed(axiom);
        } catch (Exception e) {
            LOGGER.debug("Error checking entailment for axiom: {}", axiom, e);
            return false;
        }
    }

    /**
     * Get inferred types for an individual
     */
    public java.util.Set<OWLClass> getTypes(OWLNamedIndividual individual, boolean direct) {
        if (reasoner == null) {
            return java.util.Collections.emptySet();
        }

        try {
            return reasoner.getTypes(individual, direct).getFlattened();
        } catch (Exception e) {
            LOGGER.debug("Error getting types for individual: {}", individual, e);
            return java.util.Collections.emptySet();
        }
    }

    /**
     * Get object property values for an individual
     */
    public java.util.Set<OWLNamedIndividual> getObjectPropertyValues(OWLNamedIndividual individual, OWLObjectProperty property) {
        if (reasoner == null) {
            return java.util.Collections.emptySet();
        }

        try {
            return reasoner.getObjectPropertyValues(individual, property).getFlattened();
        } catch (Exception e) {
            LOGGER.debug("Error getting property values for individual: {} property: {}", individual, property, e);
            return java.util.Collections.emptySet();
        }
    }

    @Override
    public void close() {
        if (reasoner != null) {
            try {
                reasoner.dispose();
                LOGGER.info("Reasoner disposed successfully");
            } catch (Exception e) {
                LOGGER.warn("Error disposing reasoner", e);
            } finally {
                reasoner = null;
            }
        }
    }
}