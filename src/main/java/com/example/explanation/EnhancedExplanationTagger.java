// com/example/explanation/EnhancedExplanationTagger.java
package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * FIXED: Enhanced explanation tagger with comprehensive OWL reasoning patterns
 * Tags each axiom individually but prevents redundant duplicate tags
 */
public class EnhancedExplanationTagger {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnhancedExplanationTagger.class);

    // Tag constants
    public static final String TAG_DIRECT = "D";           // Direct assertion
    public static final String TAG_TRANSITIVITY = "T";     // Transitive property
    public static final String TAG_HIERARCHY = "H";        // Subclass/subproperty hierarchy
    public static final String TAG_INVERSE = "I";          // Inverse property
    public static final String TAG_SYMMETRY = "S";         // Symmetric property
    public static final String TAG_FUNCTIONAL = "F";       // Functional property
    public static final String TAG_EQUIVALENCE = "Q";      // Equivalence (owl:equivalentClass, etc.)
    public static final String TAG_DISJOINT = "J";         // Disjoint classes/properties
    public static final String TAG_DOMAIN_RANGE = "R";     // Domain/range restrictions
    public static final String TAG_CHAIN = "N";            // Property chain
    public static final String TAG_CARDINALITY = "C";      // Cardinality restrictions
    public static final String TAG_EXISTENTIAL = "E";      // Existential restriction (owl:someValuesFrom)
    public static final String TAG_UNIVERSAL = "L";        // Universal restriction (owl:allValuesFrom)
    public static final String TAG_INTERSECTION = "∩";     // Intersection (owl:intersectionOf)
    public static final String TAG_UNION = "U";            // Union (owl:unionOf)
    public static final String TAG_COMPLEMENT = "¬";       // Complement (owl:complementOf)
    public static final String TAG_REFLEXIVE = "V";        // Reflexive property
    public static final String TAG_IRREFLEXIVE = "Y";      // Irreflexive property
    public static final String TAG_ASYMMETRIC = "A";       // Asymmetric property
    public static final String TAG_MULTI_STEP = "M";       // Multi-step reasoning (>2 TBox axioms)

    /**
     * FIXED: Tag explanation path by counting EACH axiom individually
     **/
    public String tagExplanation(ExplanationPath path) {
        if (path == null || (path.getAxioms().isEmpty() &&
                (path.getJustifications() == null || path.getJustifications().isEmpty()))) {
            return TAG_MULTI_STEP;
        }

        List<String> tagSequence = new ArrayList<>();
        Set<String> processedAxioms = new HashSet<>();
        Set<String> tboxAxiomTypes = new HashSet<>(); // Track TYPES of TBox axioms
        Set<String> aboxAxioms = new HashSet<>();

        // STEP 1: Analyze EACH axiom individually
        for (OWLAxiom axiom : path.getAxioms()) {
            String axiomStr = axiom.toString();

            if (!processedAxioms.contains(axiomStr)) {
                String tag = tagSingleAxiom(axiom);
                if (!tag.isEmpty()) {
                    tagSequence.add(tag);
                    LOGGER.debug("Axiom: {} -> Tag: {}", axiomStr, tag);
                }

                processedAxioms.add(axiomStr);

                // Classify and track TBox axiom TYPES
                if (isTBoxAxiom(axiom)) {
                    String axiomType = getAxiomTypeForMultiStep(axiom);
                    tboxAxiomTypes.add(axiomType);
                } else {
                    aboxAxioms.add(axiomStr);
                }
            }
        }

        // STEP 2: Analyze justifications (same as before)
        Set<String> processedJustifications = new HashSet<>();
        if (path.getJustifications() != null && !path.getJustifications().isEmpty()) {
            for (String justification : path.getJustifications()) {
                if (!justification.trim().isEmpty() && !processedJustifications.contains(justification)) {
                    if (!isRedundantJustification(justification, processedAxioms)) {
                        String tag = tagJustificationString(justification);
                        if (!tag.isEmpty()) {
                            tagSequence.add(tag);
                            LOGGER.debug("Justification: {} -> Tag: {}", justification, tag);
                        }

                        // Classify justification TBox types
                        if (isTBoxJustification(justification)) {
                            String justificationType = getJustificationTypeForMultiStep(justification);
                            tboxAxiomTypes.add(justificationType);
                        } else {
                            aboxAxioms.add(justification);
                        }
                    }
                    processedJustifications.add(justification);
                }
            }
        }

        // STEP 3: Add multi-step tag if 2+ different TBox axiom types
        if (tboxAxiomTypes.size() >= 2) {
            tagSequence.add(TAG_MULTI_STEP);
            LOGGER.debug("Added multi-step tag: {} different TBox types: {}",
                    tboxAxiomTypes.size(), tboxAxiomTypes);
        }

        // STEP 4: Build final tag
        String finalTag = buildOrderedTagString(tagSequence);

        LOGGER.debug("Final tag for path '{}': {} (TBox types: {})",
                path.getDescription(), finalTag, tboxAxiomTypes);

        return !finalTag.isEmpty() ? finalTag : TAG_MULTI_STEP;
    }

    /**
     * NEW: Get axiom type for multi-step detection
     */
    private String getAxiomTypeForMultiStep(OWLAxiom axiom) {
        // Group related axiom types together
        if (axiom instanceof OWLSubClassOfAxiom || axiom instanceof OWLSubObjectPropertyOfAxiom ||
                axiom instanceof OWLSubDataPropertyOfAxiom) {
            return "HIERARCHY";
        }

        if (axiom instanceof OWLEquivalentClassesAxiom || axiom instanceof OWLEquivalentObjectPropertiesAxiom ||
                axiom instanceof OWLEquivalentDataPropertiesAxiom) {
            return "EQUIVALENCE";
        }

        if (axiom instanceof OWLObjectPropertyDomainAxiom || axiom instanceof OWLObjectPropertyRangeAxiom ||
                axiom instanceof OWLDataPropertyDomainAxiom || axiom instanceof OWLDataPropertyRangeAxiom) {
            return "DOMAIN_RANGE";
        }

        if (axiom instanceof OWLTransitiveObjectPropertyAxiom) {
            return "TRANSITIVITY";
        }

        if (axiom instanceof OWLSymmetricObjectPropertyAxiom) {
            return "SYMMETRY";
        }

        if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
            return "INVERSE";
        }

        if (axiom instanceof OWLFunctionalObjectPropertyAxiom || axiom instanceof OWLFunctionalDataPropertyAxiom ||
                axiom instanceof OWLInverseFunctionalObjectPropertyAxiom) {
            return "FUNCTIONAL";
        }

        if (axiom instanceof OWLSubPropertyChainOfAxiom) {
            return "PROPERTY_CHAIN";
        }

        if (axiom instanceof OWLDisjointClassesAxiom || axiom instanceof OWLDisjointObjectPropertiesAxiom ||
                axiom instanceof OWLDisjointDataPropertiesAxiom) {
            return "DISJOINT";
        }

        // For complex class expressions, analyze content
        String axiomString = axiom.toString().toLowerCase();
        if (axiomString.contains("intersectionof")) {
            return "INTERSECTION";
        }
        if (axiomString.contains("unionof")) {
            return "UNION";
        }
        if (axiomString.contains("somevaluesfrom")) {
            return "EXISTENTIAL";
        }
        if (axiomString.contains("allvaluesfrom")) {
            return "UNIVERSAL";
        }
        if (axiomString.contains("cardinality")) {
            return "CARDINALITY";
        }

        return "OTHER";
    }

    /**
     * NEW: Get justification type for multi-step detection
     */
    private String getJustificationTypeForMultiStep(String justification) {
        String lower = justification.toLowerCase().trim();

        if (lower.contains("subclassof") || lower.contains("subpropertyof")) {
            return "HIERARCHY";
        }
        if (lower.contains("equivalentclass") || lower.contains("equivalentproperty")) {
            return "EQUIVALENCE";
        }
        if (lower.contains("domain") || lower.contains("range")) {
            return "DOMAIN_RANGE";
        }
        if (lower.contains("transitive")) {
            return "TRANSITIVITY";
        }
        if (lower.contains("symmetric")) {
            return "SYMMETRY";
        }
        if (lower.contains("inverse")) {
            return "INVERSE";
        }
        if (lower.contains("functional")) {
            return "FUNCTIONAL";
        }
        if (lower.contains("propertychain")) {
            return "PROPERTY_CHAIN";
        }
        if (lower.contains("disjoint")) {
            return "DISJOINT";
        }
        if (lower.contains("intersectionof") || lower.contains("intersection")) {
            return "INTERSECTION";
        }
        if (lower.contains("unionof") || lower.contains("union")) {
            return "UNION";
        }
        if (lower.contains("somevaluesfrom")) {
            return "EXISTENTIAL";
        }
        if (lower.contains("allvaluesfrom")) {
            return "UNIVERSAL";
        }
        if (lower.contains("cardinality")) {
            return "CARDINALITY";
        }

        return "OTHER";
    }

    /**
     * UPDATED: Build ordered tag string
     */
    private String buildOrderedTagString(List<String> tagSequence) {
        if (tagSequence.isEmpty()) return "";

        Map<String, Integer> tagCounts = new LinkedHashMap<>();
        for (String tag : tagSequence) {
            tagCounts.merge(tag, 1, Integer::sum);
        }

        StringBuilder result = new StringBuilder();
        List<String> orderedTags = Arrays.asList(
                TAG_DIRECT, TAG_HIERARCHY, TAG_EQUIVALENCE, TAG_TRANSITIVITY,
                TAG_SYMMETRY, TAG_INVERSE, TAG_FUNCTIONAL, TAG_CHAIN,
                TAG_EXISTENTIAL, TAG_UNIVERSAL, TAG_CARDINALITY,
                TAG_INTERSECTION, TAG_UNION, TAG_DOMAIN_RANGE,
                TAG_MULTI_STEP, TAG_DISJOINT, TAG_REFLEXIVE,
                TAG_ASYMMETRIC, TAG_IRREFLEXIVE, TAG_COMPLEMENT
        );

        for (String tag : orderedTags) {
            int count = tagCounts.getOrDefault(tag, 0);
            for (int i = 0; i < count; i++) {
                result.append(tag);
            }
        }

        return result.toString();
    }

    /**
     * Check if justification is redundant (just a string form of an already-processed axiom)
     */
    private boolean isRedundantJustification(String justification, Set<String> processedAxioms) {
        String justLower = justification.toLowerCase().trim();

        // Check if this justification is just describing an axiom we already processed
        for (String axiomStr : processedAxioms) {
            String axiomLower = axiomStr.toLowerCase();

            // Simple heuristic: if justification contains key parts of the axiom, it's likely redundant
            if (justLower.contains("rdf:type") && axiomLower.contains("classassertion") ||
                    justLower.contains("rdfs:subclassof") && axiomLower.contains("subclassof") ||
                    justLower.contains("domain(") && axiomLower.contains("domain") ||
                    justLower.contains("range(") && axiomLower.contains("range")) {
                return true;
            }
        }

        return false;
    }

    /**
     * ENHANCED: Tag a single axiom with comprehensive pattern matching
     */
    private String tagSingleAxiom(OWLAxiom axiom) {
        if (axiom == null) return "";

        // Direct assertions (ABox)
        if (axiom instanceof OWLClassAssertionAxiom || axiom instanceof OWLObjectPropertyAssertionAxiom ||
                axiom instanceof OWLDataPropertyAssertionAxiom) {
            return TAG_DIRECT;
        }

        // Hierarchy (TBox)
        if (axiom instanceof OWLSubClassOfAxiom || axiom instanceof OWLSubObjectPropertyOfAxiom ||
                axiom instanceof OWLSubDataPropertyOfAxiom) {
            return TAG_HIERARCHY;
        }

        // Equivalence (TBox)
        if (axiom instanceof OWLEquivalentClassesAxiom || axiom instanceof OWLEquivalentObjectPropertiesAxiom ||
                axiom instanceof OWLEquivalentDataPropertiesAxiom) {
            return TAG_EQUIVALENCE;
        }

        // Disjointness (TBox)
        if (axiom instanceof OWLDisjointClassesAxiom || axiom instanceof OWLDisjointObjectPropertiesAxiom ||
                axiom instanceof OWLDisjointDataPropertiesAxiom) {
            return TAG_DISJOINT;
        }

        // Property characteristics (TBox)
        if (axiom instanceof OWLTransitiveObjectPropertyAxiom) {
            return TAG_TRANSITIVITY;
        }
        if (axiom instanceof OWLSymmetricObjectPropertyAxiom) {
            return TAG_SYMMETRY;
        }
        if (axiom instanceof OWLAsymmetricObjectPropertyAxiom) {
            return TAG_ASYMMETRIC;
        }
        if (axiom instanceof OWLReflexiveObjectPropertyAxiom) {
            return TAG_REFLEXIVE;
        }
        if (axiom instanceof OWLIrreflexiveObjectPropertyAxiom) {
            return TAG_IRREFLEXIVE;
        }
        if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
            return TAG_INVERSE;
        }
        if (axiom instanceof OWLFunctionalObjectPropertyAxiom || axiom instanceof OWLFunctionalDataPropertyAxiom ||
                axiom instanceof OWLInverseFunctionalObjectPropertyAxiom) {
            return TAG_FUNCTIONAL;
        }

        // Domain and range (TBox)
        if (axiom instanceof OWLObjectPropertyDomainAxiom || axiom instanceof OWLObjectPropertyRangeAxiom ||
                axiom instanceof OWLDataPropertyDomainAxiom || axiom instanceof OWLDataPropertyRangeAxiom) {
            return TAG_DOMAIN_RANGE;
        }

        // Property chains (TBox)
        if (axiom instanceof OWLSubPropertyChainOfAxiom) {
            return TAG_CHAIN;
        }

        // Analyze complex class expressions in the axiom
        return analyzeComplexAxiom(axiom);
    }

    /**
     * ENHANCED: Analyze complex axioms for specific class expression patterns
     */
    private String analyzeComplexAxiom(OWLAxiom axiom) {
        String axiomString = axiom.toString().toLowerCase();

        // Look for class expressions that indicate specific reasoning patterns
        if (axiomString.contains("objectintersectionof") || axiomString.contains("intersectionof")) {
            return TAG_INTERSECTION;
        }
        if (axiomString.contains("objectunionof") || axiomString.contains("unionof")) {
            return TAG_UNION;
        }
        if (axiomString.contains("objectcomplementof") || axiomString.contains("complementof")) {
            return TAG_COMPLEMENT;
        }
        if (axiomString.contains("objectsomevaluesfrom") || axiomString.contains("somevaluesfrom")) {
            return TAG_EXISTENTIAL;
        }
        if (axiomString.contains("objectallvaluesfrom") || axiomString.contains("allvaluesfrom")) {
            return TAG_UNIVERSAL;
        }
        if (axiomString.contains("objectmincardinality") || axiomString.contains("objectmaxcardinality") ||
                axiomString.contains("objectexactcardinality") || axiomString.contains("cardinality")) {
            return TAG_CARDINALITY;
        }

        // If we can't categorize it, it's complex
        return "";
    }

    /**
     * ENHANCED: Tag justification string by analyzing its semantic content
     */
    private String tagJustificationString(String justification) {
        if (justification == null || justification.trim().isEmpty()) {
            return "";
        }

        String lower = justification.toLowerCase().trim();

        // Direct assertions - look for simple type/property assertions
        if ((lower.contains("rdf:type") || lower.contains("a ")) &&
                !lower.contains("subclassof") && !lower.contains("subpropertyof") &&
                !lower.contains("domain") && !lower.contains("range")) {
            return TAG_DIRECT;
        }

        // Hierarchy - subclass/subproperty relationships
        if (lower.contains("rdfs:subclassof") || lower.contains("subclassof") ||
                lower.contains("rdfs:subpropertyof") || lower.contains("subpropertyof") ||
                lower.contains("⊑")) {
            return TAG_HIERARCHY;
        }

        // Equivalence
        if (lower.contains("owl:equivalentclass") || lower.contains("equivalentclass") ||
                lower.contains("owl:equivalentproperty") || lower.contains("equivalentproperty") ||
                lower.contains("≡")) {
            return TAG_EQUIVALENCE;
        }

        // Domain and range restrictions
        if (lower.contains("domain(") || lower.contains("range(") ||
                lower.contains("rdfs:domain") || lower.contains("rdfs:range")) {
            return TAG_DOMAIN_RANGE;
        }

        // Property characteristics
        if (lower.contains("transitiveobjectproperty") || lower.contains("transitive")) {
            return TAG_TRANSITIVITY;
        }
        if (lower.contains("symmetricobjectproperty") || lower.contains("symmetric")) {
            return TAG_SYMMETRY;
        }
        if (lower.contains("asymmetricobjectproperty") || lower.contains("asymmetric")) {
            return TAG_ASYMMETRIC;
        }
        if (lower.contains("reflexiveobjectproperty") || lower.contains("reflexive")) {
            return TAG_REFLEXIVE;
        }
        if (lower.contains("irreflexiveobjectproperty") || lower.contains("irreflexive")) {
            return TAG_IRREFLEXIVE;
        }
        if (lower.contains("inverseof") || lower.contains("inverse") || lower.contains("⁻")) {
            return TAG_INVERSE;
        }
        if (lower.contains("functionalobjectproperty") || lower.contains("functional")) {
            return TAG_FUNCTIONAL;
        }

        // Disjointness
        if (lower.contains("owl:disjointwith") || lower.contains("disjoint")) {
            return TAG_DISJOINT;
        }

        // Property chains
        if (lower.contains("propertychain") || lower.contains("∘") || lower.contains("○")) {
            return TAG_CHAIN;
        }

        // Restrictions
        if (lower.contains("somevaluesfrom") || lower.contains("∃")) {
            return TAG_EXISTENTIAL;
        }
        if (lower.contains("allvaluesfrom") || lower.contains("∀")) {
            return TAG_UNIVERSAL;
        }
        if (lower.contains("cardinality") || lower.contains("min ") || lower.contains("max ") ||
                lower.contains("exactly ")) {
            return TAG_CARDINALITY;
        }

        // Complex expressions
        if (lower.contains("intersectionof") || lower.contains("∩") ||
                lower.contains("intersection") || lower.contains("member of all")) {
            return TAG_INTERSECTION;
        }
        if (lower.contains("unionof") || lower.contains("∪") || lower.contains("union")) {
            return TAG_UNION;
        }
        if (lower.contains("complementof") || lower.contains("¬") || lower.contains("complement")) {
            return TAG_COMPLEMENT;
        }

        // If we can't categorize the justification, don't tag it
        return "";
    }

    /**
     * Check if axiom belongs to TBox (terminological box)
     */
    private boolean isTBoxAxiom(OWLAxiom axiom) {
        return axiom instanceof OWLSubClassOfAxiom ||
                axiom instanceof OWLEquivalentClassesAxiom ||
                axiom instanceof OWLDisjointClassesAxiom ||
                axiom instanceof OWLSubObjectPropertyOfAxiom ||
                axiom instanceof OWLSubDataPropertyOfAxiom ||
                axiom instanceof OWLEquivalentObjectPropertiesAxiom ||
                axiom instanceof OWLEquivalentDataPropertiesAxiom ||
                axiom instanceof OWLObjectPropertyDomainAxiom ||
                axiom instanceof OWLObjectPropertyRangeAxiom ||
                axiom instanceof OWLDataPropertyDomainAxiom ||
                axiom instanceof OWLDataPropertyRangeAxiom ||
                axiom instanceof OWLTransitiveObjectPropertyAxiom ||
                axiom instanceof OWLSymmetricObjectPropertyAxiom ||
                axiom instanceof OWLAsymmetricObjectPropertyAxiom ||
                axiom instanceof OWLReflexiveObjectPropertyAxiom ||
                axiom instanceof OWLIrreflexiveObjectPropertyAxiom ||
                axiom instanceof OWLFunctionalObjectPropertyAxiom ||
                axiom instanceof OWLFunctionalDataPropertyAxiom ||
                axiom instanceof OWLInverseFunctionalObjectPropertyAxiom ||
                axiom instanceof OWLInverseObjectPropertiesAxiom ||
                axiom instanceof OWLSubPropertyChainOfAxiom;
    }

    /**
     * Check if justification string represents TBox knowledge
     */
    private boolean isTBoxJustification(String justification) {
        String lower = justification.toLowerCase();
        return lower.contains("subclassof") ||
                lower.contains("subpropertyof") ||
                lower.contains("equivalentclass") ||
                lower.contains("equivalentproperty") ||
                lower.contains("disjoint") ||
                lower.contains("domain") ||
                lower.contains("range") ||
                lower.contains("transitive") ||
                lower.contains("symmetric") ||
                lower.contains("functional") ||
                lower.contains("inverse") ||
                lower.contains("propertychain") ||
                lower.contains("somevaluesfrom") ||
                lower.contains("allvaluesfrom") ||
                lower.contains("cardinality") ||
                lower.contains("intersectionof") ||
                lower.contains("unionof") ||
                lower.contains("complementof");
    }

}