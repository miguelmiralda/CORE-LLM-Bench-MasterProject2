package com.example.difficulty;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Computes the ABox size of an ontology/subgraph.
 *
 * ABox size = number of assertion axioms in the ontology context.
 *
 * We count:
 * - class assertions
 * - object property assertions
 * - data property assertions
 * - same individual axioms
 * - different individuals axioms
 * - negative object property assertions
 * - negative data property assertions
 *
 * We do NOT count TBox axioms such as:
 * - subclass axioms
 * - equivalent class axioms
 * - domain/range axioms
 * - property hierarchy axioms
 */
public class ABoxSizeCalculator {

    public int calculate(OWLOntology ontology) {
        if (ontology == null) {
            return 0;
        }

        return countABoxAssertionAxioms(ontology, Imports.INCLUDED);
    }

    /**
     * Use this only if you want to ignore imported ontologies.
     */
    public int calculateLocalOnly(OWLOntology ontology) {
        if (ontology == null) {
            return 0;
        }

        return countABoxAssertionAxioms(ontology, Imports.EXCLUDED);
    }

    private int countABoxAssertionAxioms(OWLOntology ontology, Imports importsMode) {
        int count = 0;

        count += ontology.getAxioms(AxiomType.CLASS_ASSERTION, importsMode).size();
        count += ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION, importsMode).size();
        count += ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION, importsMode).size();

        count += ontology.getAxioms(AxiomType.SAME_INDIVIDUAL, importsMode).size();
        count += ontology.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS, importsMode).size();

        count += ontology.getAxioms(AxiomType.NEGATIVE_OBJECT_PROPERTY_ASSERTION, importsMode).size();
        count += ontology.getAxioms(AxiomType.NEGATIVE_DATA_PROPERTY_ASSERTION, importsMode).size();

        return count;
    }
}
