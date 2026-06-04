package com.example.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates SPARQL triples into natural language questions using 4 strategies.
 * The 4 strategies let us measure whether question phrasing affects LLM accuracy.
 *
 *  DIRECT     - plain simple English
 *  CONTEXTUAL - includes ontology context in the question itself
 *  RELATIONAL - frames question from the object/class perspective
 *  FORMAL     - mirrors the logical/SPARQL structure closely
 */
public class NaturalLanguageVerbalisationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NaturalLanguageVerbalisationService.class);

    public enum TranslationStrategy {
        DIRECT, CONTEXTUAL, RELATIONAL, FORMAL
    }

    // ── Binary (yes/no) ────────────────────────────────────────────────────

    public String verbaliseBinaryQuery(String subject, String predicate, String object,
                                       TranslationStrategy strategy) {
        String s = localName(subject);
        String o = localName(object);
        String p = verbalisePredicate(predicate);

        if (isRdfType(predicate)) {
            switch (strategy) {
                case DIRECT:
                    return String.format("Is \"%s\" a \"%s\"?", s, o);
                case CONTEXTUAL:
                    return String.format(
                        "In the context of this ontology, where \"%s\" is defined as a class, " +
                        "does the individual \"%s\" belong to it?", o, s);
                case RELATIONAL:
                    return String.format(
                        "Among all individuals classified as \"%s\", is \"%s\" one of them?", o, s);
                case FORMAL:
                    return String.format(
                        "According to the class assertion axioms in the ontology, " +
                        "does the individual \"%s\" instantiate the class \"%s\"?", s, o);
            }
        } else {
            switch (strategy) {
                case DIRECT:
                    return String.format("Does \"%s\" %s \"%s\"?", s, p, o);
                case CONTEXTUAL:
                    return String.format(
                        "Given that \"%s\" is a defined property in this ontology, " +
                        "does \"%s\" have the value \"%s\" for that property?", p, s, o);
                case RELATIONAL:
                    return String.format("Is \"%s\" the %s of \"%s\"?", o, p, s);
                case FORMAL:
                    return String.format(
                        "According to the property assertion axioms in the ontology, " +
                        "is it entailed that the object property \"%s\" holds " +
                        "between individual \"%s\" and individual \"%s\"?", p, s, o);
            }
        }
        return String.format("Is \"%s\" related to \"%s\"?", s, o);
    }

    // ── Multi-choice ────────────────────────────────────────────────────────

    public String verbaliseMultiChoiceQuery(String subject, String predicate,
                                            TranslationStrategy strategy) {
        String s = localName(subject);
        String p = verbalisePredicate(predicate);

        if (isRdfType(predicate)) {
            switch (strategy) {
                case DIRECT:
                    return String.format("What classes does \"%s\" belong to?", s);
                case CONTEXTUAL:
                    return String.format(
                        "Based on the class hierarchy defined in this ontology, " +
                        "which classes is \"%s\" a member of?", s);
                case RELATIONAL:
                    return String.format(
                        "List all ontology classes where \"%s\" appears as an instance.", s);
                case FORMAL:
                    return String.format(
                        "List all classes C such that the ontology entails " +
                        "ClassAssertion(C, \"%s\").", s);
            }
        } else {
            switch (strategy) {
                case DIRECT:
                    return String.format(
                        "What are all the values of \"%s\" for \"%s\"?", p, s);
                case CONTEXTUAL:
                    return String.format(
                        "Using the property \"%s\" as defined in this ontology, " +
                        "what are all the values that \"%s\" is related to?", p, s);
                case RELATIONAL:
                    return String.format(
                        "Which individuals are related to \"%s\" via the property \"%s\"?", s, p);
                case FORMAL:
                    return String.format(
                        "List all individuals Y such that the ontology entails " +
                        "ObjectPropertyAssertion(\"%s\", \"%s\", Y).", p, s);
            }
        }
        return String.format("What are all values for \"%s\"?", s);
    }

    // ── Predicate helper ───────────────────────────────────────────────────

    public String verbalisePredicate(String predicate) {
        if (predicate == null) return "is related to";
        if (isRdfType(predicate)) return "is a";
        String local = localName(predicate);
        if (local == null || local.isEmpty()) local = predicate;
        // Split CamelCase: "hasParent" → "has parent"
        return local.replaceAll("([a-z])([A-Z])", "$1 $2")
                    .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                    .toLowerCase().trim();
    }

    /** Complexity score: word count of a question (proxy for linguistic complexity). */
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