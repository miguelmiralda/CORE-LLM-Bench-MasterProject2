// com/example/util/OntologyUtils.java
package com.example.util;

import com.example.processing.SmallOntologiesProcessor;
import org.semanticweb.owlapi.model.*;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for working with OWL ontologies
 */
public class OntologyUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(OntologyUtils.class);

    /**
     * Get short form of an entity (extract name from IRI)
     */
    public static String getShortForm(Object obj) {
        if (obj == null) return "null";

        if (obj instanceof OWLEntity) {
            OWLEntity entity = (OWLEntity) obj;
            String iri = entity.getIRI().toString();
            if (iri.contains("#")) {
                return iri.substring(iri.lastIndexOf("#") + 1);
            } else if (iri.contains("/")) {
                return iri.substring(iri.lastIndexOf("/") + 1);
            }
            return iri;
        }

        if (obj instanceof OWLClassExpression) {
            OWLClassExpression expr = (OWLClassExpression) obj;
            if (expr.isAnonymous()) {
                return expr.toString();
            } else {
                return getShortForm(expr.asOWLClass());
            }
        }

        if (obj instanceof OWLObjectPropertyExpression) {
            OWLObjectPropertyExpression expr = (OWLObjectPropertyExpression) obj;
            if (expr.isAnonymous()) {
                return expr.toString();
            } else {
                return getShortForm(expr.asOWLObjectProperty());
            }
        }

        return obj.toString();
    }

    /**
     * Format axiom in human-readable form
     */
    public static String formatAxiom(OWLAxiom axiom) {
        if (axiom == null) return "";

        // Format specific axiom types in human-readable form
        if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom ca = (OWLClassAssertionAxiom) axiom;
            return getShortForm(ca.getIndividual()) + " rdf:type " + getShortForm(ca.getClassExpression());
        }

        if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom opa = (OWLObjectPropertyAssertionAxiom) axiom;
            return getShortForm(opa.getSubject()) + " " + getShortForm(opa.getProperty()) + " " + getShortForm(opa.getObject());
        }

        if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom sca = (OWLSubClassOfAxiom) axiom;
            return getShortForm(sca.getSubClass()) + " rdfs:subClassOf " + getShortForm(sca.getSuperClass());
        }

        if (axiom instanceof OWLEquivalentClassesAxiom) {
            OWLEquivalentClassesAxiom eca = (OWLEquivalentClassesAxiom) axiom;
            List<String> classes = eca.getClassExpressions().stream()
                    .map(OntologyUtils::getShortForm)
                    .collect(Collectors.toList());
            return String.join(" owl:equivalentClass ", classes);
        }

        if (axiom instanceof OWLObjectPropertyDomainAxiom) {
            OWLObjectPropertyDomainAxiom opda = (OWLObjectPropertyDomainAxiom) axiom;
            return "domain(" + getShortForm(opda.getProperty()) + ") = " + getShortForm(opda.getDomain());
        }

        if (axiom instanceof OWLObjectPropertyRangeAxiom) {
            OWLObjectPropertyRangeAxiom opra = (OWLObjectPropertyRangeAxiom) axiom;
            return "range(" + getShortForm(opra.getProperty()) + ") = " + getShortForm(opra.getRange());
        }

        if (axiom instanceof OWLSubObjectPropertyOfAxiom) {
            OWLSubObjectPropertyOfAxiom sopa = (OWLSubObjectPropertyOfAxiom) axiom;
            return getShortForm(sopa.getSubProperty()) + " rdfs:subPropertyOf " + getShortForm(sopa.getSuperProperty());
        }

        if (axiom instanceof OWLTransitiveObjectPropertyAxiom) {
            OWLTransitiveObjectPropertyAxiom topa = (OWLTransitiveObjectPropertyAxiom) axiom;
            return "TransitiveObjectProperty(" + getShortForm(topa.getProperty()) + ")";
        }

        if (axiom instanceof OWLSymmetricObjectPropertyAxiom) {
            OWLSymmetricObjectPropertyAxiom sopa = (OWLSymmetricObjectPropertyAxiom) axiom;
            return "SymmetricObjectProperty(" + getShortForm(sopa.getProperty()) + ")";
        }

        if (axiom instanceof OWLFunctionalObjectPropertyAxiom) {
            OWLFunctionalObjectPropertyAxiom fopa = (OWLFunctionalObjectPropertyAxiom) axiom;
            return "FunctionalObjectProperty(" + getShortForm(fopa.getProperty()) + ")";
        }

        if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
            OWLInverseObjectPropertiesAxiom iopa = (OWLInverseObjectPropertiesAxiom) axiom;
            List<String> props = iopa.getProperties().stream()
                    .map(OntologyUtils::getShortForm)
                    .collect(Collectors.toList());
            return String.join(" owl:inverseOf ", props);
        }

        // Fallback to toString with short forms where possible
        String axiomStr = axiom.toString();
        return axiomStr;
    }

    /**
     * Escape JSON special characters
     */
    public static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Check if class is owl:Thing
     */
    public static boolean isOwlThing(OWLClass clazz) {
        return clazz.isOWLThing();
    }

    /**
     * Create triple key for storage
     */
    public static String createTripleKey(String subject, String predicate, String object) {
        return subject + "|" + predicate + "|" + object;
    }

    /**
     * Parse triple key back to components
     */
    public static String[] parseTripleKey(String tripleKey) {
        return tripleKey.split("\\|", 3);
    }

    /**
     * Calculate TBox size as the sum of classes and properties
     */
    public static int calculateTBoxSize(OWLOntology ontology) {
        try {
            // Count classes (excluding owl:Thing)
            long classCount = ontology.getClassesInSignature().stream()
                    .filter(cls -> !cls.isOWLThing())
                    .count();

            // Count object properties
            long objectPropertyCount = ontology.getObjectPropertiesInSignature().size();

            // Count data properties
            long dataPropertyCount = ontology.getDataPropertiesInSignature().size();

            // Count annotation properties
            long annotationPropertyCount = ontology.getAnnotationPropertiesInSignature().size();

            // TBox = Classes + Object Properties + Data Properties + Annotation Properties
            int tboxSize = (int) (classCount + objectPropertyCount + dataPropertyCount + annotationPropertyCount);

            LOGGER.debug("TBox calculation: {} classes, {} object properties, {} data properties, {} annotation properties = {} total",
                    classCount, objectPropertyCount, dataPropertyCount, annotationPropertyCount, tboxSize);

            return tboxSize;

        } catch (Exception e) {
            LOGGER.warn("Error calculating TBox size: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Calculate ABox size (number of individuals)
     */
    public static int calculateABoxSize(OWLOntology ontology) {
        return ontology.getIndividualsInSignature().size();
    }

    /**
     * Check if an axiom is part of the TBox (terminological)
     */
    public static boolean isTBoxAxiom(OWLAxiom axiom) {  // Changed from private to public static
        return axiom instanceof OWLSubClassOfAxiom ||
                axiom instanceof OWLEquivalentClassesAxiom ||
                axiom instanceof OWLDisjointClassesAxiom ||
                axiom instanceof OWLSubObjectPropertyOfAxiom ||
                axiom instanceof OWLEquivalentObjectPropertiesAxiom ||
                axiom instanceof OWLObjectPropertyDomainAxiom ||
                axiom instanceof OWLObjectPropertyRangeAxiom ||
                axiom instanceof OWLTransitiveObjectPropertyAxiom ||
                axiom instanceof OWLSymmetricObjectPropertyAxiom ||
                axiom instanceof OWLFunctionalObjectPropertyAxiom ||
                axiom instanceof OWLInverseFunctionalObjectPropertyAxiom ||
                axiom instanceof OWLInverseObjectPropertiesAxiom ||
                axiom instanceof OWLSubDataPropertyOfAxiom ||
                axiom instanceof OWLDataPropertyDomainAxiom ||
                axiom instanceof OWLDataPropertyRangeAxiom ||
                axiom instanceof OWLFunctionalDataPropertyAxiom ||
                axiom instanceof OWLSubPropertyChainOfAxiom ||
                axiom instanceof OWLDisjointObjectPropertiesAxiom ||
                axiom instanceof OWLDisjointDataPropertiesAxiom;
    }

    /**
     * Count occurrences of concept (class) and role (property) names in an axiom
     */
    public static int countConceptAndRoleOccurrences(OWLAxiom axiom) {  // Changed from private to public static
        int count = 0;
        // Count class (concept) occurrences
        count += axiom.getClassesInSignature().size();
        // Count object property (role) occurrences
        count += axiom.getObjectPropertiesInSignature().size();
        // Count data property occurrences
        count += axiom.getDataPropertiesInSignature().size();
        return count;
    }
}