package com.example.query;

import com.example.explanation.ExplanationPath;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Translates SPARQL triples into natural language questions using 4 strategies.
 *
 *  DIRECT     - plain simple English
 *  CONTEXTUAL - enriched with real TBox facts from the ontology (class hierarchy, domain/range)
 *  RELATIONAL - frames question from the object/class perspective
 *  FORMAL     - includes the actual reasoning justification path that entails the fact
 */
public class NaturalLanguageVerbalisationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NaturalLanguageVerbalisationService.class);

    public enum TranslationStrategy {
        DIRECT, CONTEXTUAL, RELATIONAL, FORMAL
    }

    // ── Binary (yes/no) — without ontology context (DIRECT + RELATIONAL) ────

    public String verbaliseBinaryQuery(String subject, String predicate, String object,
                                       TranslationStrategy strategy) {
        return verbaliseBinaryQuery(subject, predicate, object, strategy, null, null);
    }

    // ── Binary (yes/no) — with ontology context ──────────────────────────────

    public String verbaliseBinaryQuery(String subject, String predicate, String object,
                                       TranslationStrategy strategy,
                                       OWLOntology ontology,
                                       Set<ExplanationPath> paths) {
        String s = localName(subject);
        String o = localName(object);
        String p = verbalisePredicate(predicate);

        if (isRdfType(predicate)) {
            switch (strategy) {
                case DIRECT:
                    return String.format("Is \"%s\" a \"%s\"?", s, o);

                case CONTEXTUAL: {
                    String tboxHint = ontology != null ? extractClassContext(ontology, object) : "";
                    if (tboxHint.isEmpty()) {
                        return String.format(
                            "In the context of this ontology, where \"%s\" is defined as a class, " +
                            "does the individual \"%s\" belong to it?", o, s);
                    }
                    return String.format(
                        "In this ontology, \"%s\" is a class%s. " +
                        "Does the individual \"%s\" belong to that class?", o, tboxHint, s);
                }

                case RELATIONAL:
                    return String.format(
                        "Among all individuals classified as \"%s\", is \"%s\" one of them?", o, s);

                case FORMAL: {
                    String justification = paths != null ? extractJustification(paths) : "";
                    if (justification.isEmpty()) {
                        return String.format(
                            "According to the class assertion axioms in the ontology, " +
                            "does the individual \"%s\" instantiate the class \"%s\"?", s, o);
                    }
                    return String.format(
                        "Given the ontology axioms [%s], " +
                        "does it logically follow that \"%s\" is an instance of \"%s\"?",
                        justification, s, o);
                }
            }
        } else {
            switch (strategy) {
                case DIRECT:
                    return String.format("Does \"%s\" %s \"%s\"?", s, p, o);

                case CONTEXTUAL: {
                    String tboxHint = ontology != null ? extractPropertyContext(ontology, predicate) : "";
                    if (tboxHint.isEmpty()) {
                        return String.format(
                            "Given that \"%s\" is a defined property in this ontology, " +
                            "does \"%s\" have the value \"%s\" for that property?", p, s, o);
                    }
                    return String.format(
                        "In this ontology, \"%s\" is a property%s. " +
                        "Does \"%s\" %s \"%s\"?", p, tboxHint, s, p, o);
                }

                case RELATIONAL:
                    return String.format("Is \"%s\" the %s of \"%s\"?", o, p, s);

                case FORMAL: {
                    String justification = paths != null ? extractJustification(paths) : "";
                    if (justification.isEmpty()) {
                        return String.format(
                            "According to the property assertion axioms in the ontology, " +
                            "is it entailed that the object property \"%s\" holds " +
                            "between individual \"%s\" and individual \"%s\"?", p, s, o);
                    }
                    return String.format(
                        "Given the ontology axioms [%s], " +
                        "is it entailed that \"%s\" %s \"%s\"?",
                        justification, s, p, o);
                }
            }
        }
        return String.format("Is \"%s\" related to \"%s\"?", s, o);
    }

    // ── Multi-choice — without ontology context ──────────────────────────────

    public String verbaliseMultiChoiceQuery(String subject, String predicate,
                                            TranslationStrategy strategy) {
        return verbaliseMultiChoiceQuery(subject, predicate, strategy, null, null);
    }

    // ── Multi-choice — with ontology context ─────────────────────────────────

    public String verbaliseMultiChoiceQuery(String subject, String predicate,
                                            TranslationStrategy strategy,
                                            OWLOntology ontology,
                                            Set<ExplanationPath> paths) {
        String s = localName(subject);
        String p = verbalisePredicate(predicate);

        if (isRdfType(predicate)) {
            switch (strategy) {
                case DIRECT:
                    return String.format("What classes does \"%s\" belong to?", s);

                case CONTEXTUAL: {
                    String tboxHint = ontology != null ? extractClassHierarchySummary(ontology) : "";
                    if (tboxHint.isEmpty()) {
                        return String.format(
                            "Based on the class hierarchy defined in this ontology, " +
                            "which classes is \"%s\" a member of?", s);
                    }
                    return String.format(
                        "This ontology defines the following class hierarchy: %s. " +
                        "Which of those classes is \"%s\" a member of?", tboxHint, s);
                }

                case RELATIONAL:
                    return String.format(
                        "List all ontology classes where \"%s\" appears as an instance.", s);

                case FORMAL: {
                    String justification = paths != null ? extractJustification(paths) : "";
                    if (justification.isEmpty()) {
                        return String.format(
                            "List all classes C such that the ontology entails " +
                            "ClassAssertion(C, \"%s\").", s);
                    }
                    return String.format(
                        "Using the axioms [%s], list all classes C such that " +
                        "ClassAssertion(C, \"%s\") is entailed.", justification, s);
                }
            }
        } else {
            switch (strategy) {
                case DIRECT:
                    return String.format(
                        "What are all the values of \"%s\" for \"%s\"?", p, s);

                case CONTEXTUAL: {
                    String tboxHint = ontology != null ? extractPropertyContext(ontology, predicate) : "";
                    if (tboxHint.isEmpty()) {
                        return String.format(
                            "Using the property \"%s\" as defined in this ontology, " +
                            "what are all the values that \"%s\" is related to?", p, s);
                    }
                    return String.format(
                        "In this ontology, \"%s\" is a property%s. " +
                        "What are all the values of \"%s\" for \"%s\"?", p, tboxHint, p, s);
                }

                case RELATIONAL:
                    return String.format(
                        "Which individuals are related to \"%s\" via the property \"%s\"?", s, p);

                case FORMAL: {
                    String justification = paths != null ? extractJustification(paths) : "";
                    if (justification.isEmpty()) {
                        return String.format(
                            "List all individuals Y such that the ontology entails " +
                            "ObjectPropertyAssertion(\"%s\", \"%s\", Y).", p, s);
                    }
                    return String.format(
                        "Using the axioms [%s], list all individuals Y such that " +
                        "ObjectPropertyAssertion(\"%s\", \"%s\", Y) is entailed.", justification, p, s);
                }
            }
        }
        return String.format("What are all values for \"%s\"?", s);
    }

    // ── TBox context extractors ───────────────────────────────────────────────

    /**
     * Extracts superclass info for a given class URI from the ontology TBox.
     * E.g. for "Woman" returns " (a subclass of: Person, Agent)"
     */
    private String extractClassContext(OWLOntology ontology, String classUri) {
        try {
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            IRI iri = resolveIRI(ontology, classUri);
            if (iri == null) return "";
            OWLClass owlClass = df.getOWLClass(iri);

            List<String> superClasses = ontology.getSubClassAxiomsForSubClass(owlClass).stream()
                    .map(ax -> ax.getSuperClass())
                    .filter(ce -> ce instanceof OWLClass && !((OWLClass) ce).isOWLThing())
                    .map(ce -> localName(((OWLClass) ce).getIRI().toString()))
                    .filter(n -> n != null && !n.isEmpty())
                    .distinct()
                    .limit(3)
                    .collect(Collectors.toList());

            if (superClasses.isEmpty()) return "";
            return " (a subclass of: " + String.join(", ", superClasses) + ")";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extracts domain and range info for a property URI from the ontology TBox.
     * E.g. for "hasParent" returns " with domain Person and range Person"
     */
    private String extractPropertyContext(OWLOntology ontology, String predicateUri) {
        try {
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            IRI iri = resolveIRI(ontology, predicateUri);
            if (iri == null) return "";
            OWLObjectProperty prop = df.getOWLObjectProperty(iri);

            List<String> domains = ontology.getObjectPropertyDomainAxioms(prop).stream()
                    .map(ax -> ax.getDomain())
                    .filter(ce -> ce instanceof OWLClass && !((OWLClass) ce).isOWLThing())
                    .map(ce -> localName(((OWLClass) ce).getIRI().toString()))
                    .filter(n -> n != null && !n.isEmpty())
                    .distinct().limit(2).collect(Collectors.toList());

            List<String> ranges = ontology.getObjectPropertyRangeAxioms(prop).stream()
                    .map(ax -> ax.getRange())
                    .filter(ce -> ce instanceof OWLClass && !((OWLClass) ce).isOWLThing())
                    .map(ce -> localName(((OWLClass) ce).getIRI().toString()))
                    .filter(n -> n != null && !n.isEmpty())
                    .distinct().limit(2).collect(Collectors.toList());

            List<String> superProps = ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).stream()
                    .filter(ax -> ax instanceof OWLSubObjectPropertyOfAxiom)
                    .map(ax -> (OWLSubObjectPropertyOfAxiom) ax)
                    .filter(ax -> ax.getSubProperty().equals(prop)
                            && ax.getSuperProperty() instanceof OWLObjectProperty)
                    .map(ax -> localName(((OWLObjectProperty) ax.getSuperProperty()).getIRI().toString()))
                    .filter(n -> n != null && !n.isEmpty())
                    .distinct().limit(2).collect(Collectors.toList());

            StringBuilder sb = new StringBuilder();
            if (!domains.isEmpty()) sb.append(" with domain ").append(String.join("/", domains));
            if (!ranges.isEmpty()) sb.append(" and range ").append(String.join("/", ranges));
            if (!superProps.isEmpty()) sb.append(", a sub-property of ").append(String.join(", ", superProps));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Builds a short summary of top-level class hierarchy for the ontology.
     */
    private String extractClassHierarchySummary(OWLOntology ontology) {
        try {
            List<String> pairs = ontology.getAxioms(AxiomType.SUBCLASS_OF).stream()
                    .filter(ax -> ax instanceof OWLSubClassOfAxiom)
                    .map(ax -> (OWLSubClassOfAxiom) ax)
                    .filter(ax -> ax.getSubClass() instanceof OWLClass
                            && ax.getSuperClass() instanceof OWLClass
                            && !((OWLClass) ax.getSuperClass()).isOWLThing())
                    .map(ax -> localName(((OWLClass) ax.getSubClass()).getIRI().toString())
                            + " ⊑ " +
                            localName(((OWLClass) ax.getSuperClass()).getIRI().toString()))
                    .distinct()
                    .limit(5)
                    .collect(Collectors.toList());

            return pairs.isEmpty() ? "" : String.join("; ", pairs);
        } catch (Exception e) {
            return "";
        }
    }

    // ── Justification extractor ───────────────────────────────────────────────

    /**
     * Picks the most informative explanation path and returns its description
     * plus up to 3 of its axioms as a readable justification string.
     */
    private String extractJustification(Set<ExplanationPath> paths) {
        if (paths == null || paths.isEmpty()) return "";
        try {
            // Prefer the most complex path (most axioms = richest justification)
            ExplanationPath best = paths.stream()
                    .max(Comparator.comparingInt(ExplanationPath::getComplexity))
                    .orElse(paths.iterator().next());

            List<String> parts = new ArrayList<>();

            // Include the human-readable description of the reasoning step
            if (best.getDescription() != null && !best.getDescription().isBlank()) {
                parts.add(best.getDescription());
            }

            // Include up to 3 axiom strings
            best.getAxioms().stream()
                    .limit(3)
                    .map(ax -> ax.toString()
                            .replaceAll("<[^>]+#([^>]+)>", "$1")  // shorten URIs to local names
                            .replaceAll("<[^>]*/([^>/]+)>", "$1"))
                    .forEach(parts::add);

            return String.join("; ", parts);
        } catch (Exception e) {
            return "";
        }
    }

    // ── IRI resolution helper ─────────────────────────────────────────────────

    private IRI resolveIRI(OWLOntology ontology, String uriOrLocalName) {
        if (uriOrLocalName == null || uriOrLocalName.isEmpty()) return null;
        if (uriOrLocalName.startsWith("http")) {
            return IRI.create(uriOrLocalName);
        }
        // Strip any namespace prefix (e.g. "rdf:type" → "type", "owl:Thing" → "Thing")
        String local = localName(uriOrLocalName);
        // Walk the full ontology signature to find a matching IRI by short form
        return ontology.getSignature().stream()
                .map(OWLEntity::getIRI)
                .filter(iri -> {
                    String sf = iri.getShortForm();
                    return sf.equals(local) || sf.equalsIgnoreCase(local);
                })
                .findFirst()
                .orElse(null);
    }

    // ── Predicate helper ───────────────────────────────────────────────────────

    public String verbalisePredicate(String predicate) {
        if (predicate == null) return "is related to";
        if (isRdfType(predicate)) return "is a";
        String local = localName(predicate);
        if (local == null || local.isEmpty()) local = predicate;
        return local.replaceAll("([a-z])([A-Z])", "$1 $2")
                    .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                    .toLowerCase().trim();
    }

    /** Word count of a question — proxy for linguistic complexity. */
    public int questionComplexity(String question) {
        if (question == null || question.isBlank()) return 0;
        return question.trim().split("\\s+").length;
    }

    private boolean isRdfType(String p) {
        return p != null && (p.equals("rdf:type") || p.endsWith("#type") ||
               p.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"));
    }

    private String localName(String uri) {
        if (uri == null || uri.isEmpty()) return uri;
        int h = uri.lastIndexOf('#');
        if (h >= 0 && h < uri.length() - 1) return uri.substring(h + 1);
        int s = uri.lastIndexOf('/');
        if (s >= 0 && s < uri.length() - 1) return uri.substring(s + 1);
        return uri;
    }
}
