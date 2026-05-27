package com.example.difficulty;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.AxiomAnnotations;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * Calculates whether a query/entailment is explicit or inferred.
 *
 * In this project, the query should already be represented as an OWLAxiom.
 *
 * Example:
 *
 * Natural-language query:
 * "Is John a Person?"
 *
 * OWL axiom behind the query:
 * ClassAssertion(Person John)
 *
 * Then this class checks:
 *
 * 1. Is ClassAssertion(Person John) directly written in the ontology?
 *      -> EXPLICIT
 *
 * 2. If not directly written, does Pellet/the OWL reasoner entail it?
 *      -> INFERRED
 *
 * 3. If the reasoner does not entail it either?
 *      -> NOT_ENTAILED
 */
public class ExplicitInferredCalculator {

    /**
     * Possible labels for a query.
     */
    public enum InferenceStatus {
        /**
         * The target axiom is directly asserted in the ontology.
         *
         * Example:
         * The ontology file directly contains:
         * John rdf:type Person
         */
        EXPLICIT,

        /**
         * The target axiom is not directly asserted,
         * but the reasoner can derive it.
         *
         * Example:
         * The ontology contains:
         * John rdf:type Student
         * Student SubClassOf Person
         *
         * Therefore the reasoner derives:
         * John rdf:type Person
         */
        INFERRED,

        /**
         * The target axiom is not directly asserted
         * and the reasoner also cannot derive it.
         */
        NOT_ENTAILED,

        /**
         * Used when input is missing.
         */
        UNKNOWN
    }

    /**
     * @param ontology   The ontology/subgraph currently being processed.
     * @param reasoner   The Pellet/OWL reasoner already initialized for this ontology.
     * @param queryAxiom The OWL axiom represented by the generated query.
     *
     * @return EXPLICIT, INFERRED, NOT_ENTAILED, or UNKNOWN.
     */
    public InferenceStatus calculate(
            OWLOntology ontology,
            OWLReasoner reasoner,
            OWLAxiom queryAxiom
    ) {
        // Safety check: if there is no ontology or no query axiom,
        // we cannot classify the query.
        if (ontology == null || queryAxiom == null) {
            return InferenceStatus.UNKNOWN;
        }

        // Step 1:
        // Check whether the axiom is directly written in the ontology.
        if (isExplicit(ontology, queryAxiom)) {
            return InferenceStatus.EXPLICIT;
        }

        // Step 2:
        // If it is not explicit, we need the reasoner to check whether it is inferred.
        if (reasoner == null) {
            return InferenceStatus.UNKNOWN;
        }

        // Step 3:
        // If Pellet entails the axiom, but the axiom was not directly written,
        // then the query is inferred.
        if (reasoner.isEntailed(queryAxiom)) {
            return InferenceStatus.INFERRED;
        }

        // Step 4:
        // If it is neither directly written nor entailed, then it is not supported.
        return InferenceStatus.NOT_ENTAILED;
    }

    /**
     * Checks whether the query axiom is directly asserted in the ontology.
     */
    public boolean isExplicit(OWLOntology ontology, OWLAxiom queryAxiom) {
        if (ontology == null || queryAxiom == null) {
            return false;
        }

        return ontology.containsAxiom(
                queryAxiom,
                Imports.INCLUDED,
                AxiomAnnotations.IGNORE_AXIOM_ANNOTATIONS
        );
    }

    /**
     * Checks only whether the query is inferred.
     *
     * Inferred means:
     * - not directly written in the ontology
     * - but entailed by the reasoner
     */
    public boolean isInferred(
            OWLOntology ontology,
            OWLReasoner reasoner,
            OWLAxiom queryAxiom
    ) {
        if (ontology == null || reasoner == null || queryAxiom == null) {
            return false;
        }

        return !isExplicit(ontology, queryAxiom)
                && reasoner.isEntailed(queryAxiom);
    }
}
