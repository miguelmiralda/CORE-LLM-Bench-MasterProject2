// com/example/explanation/ComprehensiveExplanationService.java
package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import openllet.owlapi.OpenlletReasoner;
import openllet.owlapi.explanation.PelletExplanation;
import org.semanticweb.owlapi.model.OWLAxiomVisitor;


import java.util.*;
import java.util.stream.Collectors;

import com.example.util.OntologyUtils;

/**
 * FIXED: ComprehensiveExplanationService using correct Openllet explanation API
 */
public class ComprehensiveExplanationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComprehensiveExplanationService.class);

    private OpenlletReasoner reasoner;
    private OWLOntology ontology;
    private OWLDataFactory dataFactory;
    private EnhancedExplanationTagger tagger;

    public ComprehensiveExplanationService(OpenlletReasoner reasoner, OWLOntology ontology) {
        this.reasoner = reasoner;
        this.ontology = ontology;
        this.dataFactory = ontology != null ? ontology.getOWLOntologyManager().getOWLDataFactory() : null;
        this.tagger = new EnhancedExplanationTagger();

    }

    /**
     * Find additional reasoning-based paths (your existing method)
     */
    private void addReasonerBasedPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // Add subclass reasoning paths
            addSubclassReasoningPaths(individual, clazz, allPaths);

            // Add transitive subclass paths
            findAllTransitiveSubclassPaths(individual, clazz, allPaths);

            // Add restriction-based paths
            findRestrictionBasedPaths(individual, clazz, allPaths);

            // Add domain/range paths
            addDomainRangeClassPaths(individual, clazz, allPaths);

            // Add complex constructor paths
            addComplexConstructorPaths(individual, clazz, allPaths);

        } catch (Exception e) {
            LOGGER.debug("Error in reasoner-based path finding: {}", e.getMessage());
        }
    }

    /**
     * ENHANCED: Find explanation paths that trace back to actual asserted facts
     */
    public Set<ExplanationPath> findDeepExplanationPaths(OWLNamedIndividual individual, OWLClass clazz) {
        Set<ExplanationPath> allPaths = new HashSet<>();

        try {
            LOGGER.debug("Finding DEEP explanation paths for {} : {}", getShortForm(individual), getShortForm(clazz));

            // Strategy 1: Find paths that trace back to asserted facts
            addAssertedFactTracePaths(individual, clazz, allPaths);

            // Strategy 2: Your existing comprehensive strategies as fallback
            Set<ExplanationPath> standardPaths = findAllExplanationPaths(individual, clazz);
            allPaths.addAll(standardPaths);

            // Deduplicate
            allPaths = deduplicatePaths(allPaths);

            LOGGER.info("Found {} DEEP explanation paths for {} : {}",
                    allPaths.size(), getShortForm(individual), getShortForm(clazz));

        } catch (Exception e) {
            LOGGER.error("Error finding deep explanation paths", e);
        }

        return allPaths;
    }

    /**
     * Find explanation paths that trace back to actual asserted facts
     */
    private void addAssertedFactTracePaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // For domain reasoning paths
            addDomainTraceBackPaths(individual, clazz, allPaths);

            // For range reasoning paths
            addRangeTraceBackPaths(individual, clazz, allPaths);

            // For subclass reasoning paths
            addSubclassTraceBackPaths(individual, clazz, allPaths);

        } catch (Exception e) {
            LOGGER.debug("Error in asserted fact trace paths: {}", e.getMessage());
        }
    }

    /**
     * ENHANCED: Trace domain reasoning back to asserted property facts
     */
    private void addDomainTraceBackPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // Find domain axioms for the target class
            for (OWLObjectPropertyDomainAxiom domainAxiom : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_DOMAIN)) {
                if (domainAxiom.getDomain().equals(clazz)) {
                    OWLObjectProperty domainProperty = domainAxiom.getProperty().asOWLObjectProperty();

                    // Find the ACTUAL asserted property that leads to this domain
                    List<OWLAxiom> traceBackChain = tracePropertyToAssertedFact(individual, domainProperty);

                    if (!traceBackChain.isEmpty()) {
                        // Build complete explanation chain
                        List<OWLAxiom> completeChain = new ArrayList<>(traceBackChain);
                        completeChain.add(domainAxiom);

                        List<String> justifications = new ArrayList<>();
                        for (OWLAxiom axiom : traceBackChain) {
                            justifications.add(formatAxiomForJustification(axiom));
                        }
                        justifications.add(getShortForm(domainProperty) + " Domain " + getShortForm(clazz));

                        ExplanationPath deepPath = new ExplanationPath(
                                completeChain,
                                String.format("Deep domain trace: %s domain via %d steps",
                                        getShortForm(domainProperty), traceBackChain.size()),
                                ExplanationType.DOMAIN_RANGE,
                                completeChain.size()
                        );
                        deepPath.setJustifications(justifications);
                        deepPath.setInferred(true);
                        allPaths.add(deepPath);

                        LOGGER.debug("Added deep domain trace with {} steps", traceBackChain.size());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in domain trace back: {}", e.getMessage());
        }
    }

    /**
     * CRITICAL: Trace a property back to the actual asserted fact
     */
    private List<OWLAxiom> tracePropertyToAssertedFact(OWLNamedIndividual individual, OWLObjectProperty targetProperty) {
        List<OWLAxiom> traceChain = new ArrayList<>();

        try {
            // Strategy 1: Check if individual directly has this property asserted
            Set<OWLNamedIndividual> directValues = reasoner.getObjectPropertyValues(individual, targetProperty).getFlattened();
            for (OWLNamedIndividual value : directValues) {
                OWLObjectPropertyAssertionAxiom directAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(targetProperty, individual, value);
                if (ontology.containsAxiom(directAssertion)) {
                    traceChain.add(directAssertion);
                    return traceChain; // Found direct assertion
                }
            }

            // Strategy 2: Check for sub-property reasoning
            for (OWLSubObjectPropertyOfAxiom subPropAxiom : ontology.getObjectSubPropertyAxiomsForSuperProperty(targetProperty)) {
                OWLObjectProperty subProperty = subPropAxiom.getSubProperty().asOWLObjectProperty();

                // Recursively trace the sub-property
                List<OWLAxiom> subTrace = tracePropertyToAssertedFact(individual, subProperty);
                if (!subTrace.isEmpty()) {
                    traceChain.addAll(subTrace);
                    traceChain.add(subPropAxiom);
                    return traceChain;
                }
            }

            // Strategy 3: Check for inverse property reasoning
            for (OWLInverseObjectPropertiesAxiom invAxiom : ontology.getInverseObjectPropertyAxioms(targetProperty)) {
                for (OWLObjectPropertyExpression invPropExpr : invAxiom.getProperties()) {
                    if (!invPropExpr.equals(targetProperty) && !invPropExpr.isAnonymous()) {
                        OWLObjectProperty inverseProperty = invPropExpr.asOWLObjectProperty();

                        // Check if someone has the inverse property pointing to our individual (asserted)
                        for (OWLNamedIndividual other : ontology.getIndividualsInSignature()) {
                            OWLObjectPropertyAssertionAxiom invAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(inverseProperty, other, individual);
                            if (ontology.containsAxiom(invAssertion)) {
                                traceChain.add(invAssertion);
                                traceChain.add(invAxiom);
                                return traceChain;
                            }
                        }

                        // Recursively trace the inverse property
                        List<OWLAxiom> invTrace = tracePropertyToAssertedFactReverse(individual, inverseProperty);
                        if (!invTrace.isEmpty()) {
                            traceChain.addAll(invTrace);
                            traceChain.add(invAxiom);
                            return traceChain;
                        }
                    }
                }
            }

            // Strategy 4: Check for property chain reasoning
            for (OWLSubPropertyChainOfAxiom chainAxiom : ontology.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF)) {
                if (chainAxiom.getSuperProperty().equals(targetProperty)) {
                    List<OWLObjectPropertyExpression> chain = chainAxiom.getPropertyChain();

                    if (chain.size() == 2 && !chain.get(0).isAnonymous() && !chain.get(1).isAnonymous()) {
                        OWLObjectProperty prop1 = chain.get(0).asOWLObjectProperty();
                        OWLObjectProperty prop2 = chain.get(1).asOWLObjectProperty();

                        // Find asserted facts for the property chain
                        Set<OWLNamedIndividual> intermediates = reasoner.getObjectPropertyValues(individual, prop1).getFlattened();
                        for (OWLNamedIndividual intermediate : intermediates) {
                            // Check if first step is asserted
                            OWLObjectPropertyAssertionAxiom step1Assertion = dataFactory.getOWLObjectPropertyAssertionAxiom(prop1, individual, intermediate);
                            Set<OWLNamedIndividual> targets = reasoner.getObjectPropertyValues(intermediate, prop2).getFlattened();

                            for (OWLNamedIndividual target : targets) {
                                // Check if second step is asserted
                                OWLObjectPropertyAssertionAxiom step2Assertion = dataFactory.getOWLObjectPropertyAssertionAxiom(prop2, intermediate, target);

                                if (ontology.containsAxiom(step1Assertion) && ontology.containsAxiom(step2Assertion)) {
                                    traceChain.add(step1Assertion);
                                    traceChain.add(step2Assertion);
                                    traceChain.add(chainAxiom);
                                    return traceChain;
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.debug("Error tracing property to asserted fact: {}", e.getMessage());
        }

        return traceChain; // Empty if no asserted fact found
    }

    /**
     * Helper: Trace inverse property (someone else has property pointing to individual)
     */
    private List<OWLAxiom> tracePropertyToAssertedFactReverse(OWLNamedIndividual individual, OWLObjectProperty property) {
        List<OWLAxiom> traceChain = new ArrayList<>();

        try {
            // Find who has this property pointing to our individual
            for (OWLNamedIndividual other : ontology.getIndividualsInSignature()) {
                if (reasoner.getObjectPropertyValues(other, property).getFlattened().contains(individual)) {
                    OWLObjectPropertyAssertionAxiom assertion = dataFactory.getOWLObjectPropertyAssertionAxiom(property, other, individual);
                    if (ontology.containsAxiom(assertion)) {
                        traceChain.add(assertion);
                        return traceChain;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in reverse property trace: {}", e.getMessage());
        }

        return traceChain;
    }

    /**
     * ENHANCED: Trace range reasoning back to asserted facts
     */
    private void addRangeTraceBackPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // Find range axioms for the target class
            for (OWLObjectPropertyRangeAxiom rangeAxiom : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_RANGE)) {
                if (rangeAxiom.getRange().equals(clazz)) {
                    OWLObjectProperty rangeProperty = rangeAxiom.getProperty().asOWLObjectProperty();

                    // Find who has this property pointing to our individual (asserted)
                    for (OWLNamedIndividual subject : ontology.getIndividualsInSignature()) {
                        OWLObjectPropertyAssertionAxiom assertion = dataFactory.getOWLObjectPropertyAssertionAxiom(rangeProperty, subject, individual);
                        if (ontology.containsAxiom(assertion)) {
                            List<OWLAxiom> completeChain = Arrays.asList(assertion, rangeAxiom);

                            List<String> justifications = Arrays.asList(
                                    getShortForm(subject) + " " + getShortForm(rangeProperty) + " " + getShortForm(individual),
                                    getShortForm(rangeProperty) + " Range " + getShortForm(clazz)
                            );

                            ExplanationPath deepPath = new ExplanationPath(
                                    completeChain,
                                    String.format("Deep range trace: %s range from asserted fact", getShortForm(rangeProperty)),
                                    ExplanationType.DOMAIN_RANGE,
                                    completeChain.size()
                            );
                            deepPath.setJustifications(justifications);
                            deepPath.setInferred(true);
                            allPaths.add(deepPath);

                            LOGGER.debug("Added deep range trace from asserted fact");
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in range trace back: {}", e.getMessage());
        }
    }

    /**
     * ENHANCED: Trace subclass reasoning back to asserted class memberships
     */
    private void addSubclassTraceBackPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // Get ALL asserted class memberships for the individual
            Set<OWLClass> assertedTypes = ontology.getClassAssertionAxioms(individual).stream()
                    .map(ax -> ax.getClassExpression())
                    .filter(expr -> !expr.isAnonymous())
                    .map(expr -> expr.asOWLClass())
                    .collect(Collectors.toSet());

            for (OWLClass assertedType : assertedTypes) {
                if (!assertedType.equals(clazz) && !assertedType.isOWLThing()) {
                    // Find the complete subclass chain from asserted type to target
                    List<OWLClass> subclassChain = findCompleteSubclassChain(assertedType, clazz);

                    if (!subclassChain.isEmpty()) {
                        List<OWLAxiom> completeChain = new ArrayList<>();
                        List<String> justifications = new ArrayList<>();

                        // Add initial assertion
                        OWLClassAssertionAxiom initialAssertion = dataFactory.getOWLClassAssertionAxiom(assertedType, individual);
                        completeChain.add(initialAssertion);
                        justifications.add(getShortForm(individual) + " rdf:type " + getShortForm(assertedType));

                        // Add all subclass steps
                        for (int i = 0; i < subclassChain.size() - 1; i++) {
                            OWLClass fromClass = subclassChain.get(i);
                            OWLClass toClass = subclassChain.get(i + 1);

                            OWLSubClassOfAxiom stepAxiom = dataFactory.getOWLSubClassOfAxiom(fromClass, toClass);
                            if (ontology.containsAxiom(stepAxiom)) {
                                completeChain.add(stepAxiom);
                                justifications.add(getShortForm(fromClass) + " SubClassOf " + getShortForm(toClass));
                            }
                        }

                        if (completeChain.size() > 1) {
                            ExplanationPath deepPath = new ExplanationPath(
                                    completeChain,
                                    String.format("Deep subclass trace: %s â†’ %s via %d steps",
                                            getShortForm(assertedType), getShortForm(clazz), subclassChain.size() - 1),
                                    ExplanationType.SUBSUMPTION,
                                    completeChain.size()
                            );
                            deepPath.setJustifications(justifications);
                            deepPath.setInferred(true);
                            allPaths.add(deepPath);

                            LOGGER.debug("Added deep subclass trace from {} to {} via {} steps",
                                    assertedType, clazz, subclassChain.size() - 1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in subclass trace back: {}", e.getMessage());
        }
    }

    /**
     * Find complete subclass chain from start to target class
     */
    private List<OWLClass> findCompleteSubclassChain(OWLClass startClass, OWLClass targetClass) {
        Queue<List<OWLClass>> queue = new LinkedList<>();
        Set<OWLClass> visited = new HashSet<>();

        queue.add(Arrays.asList(startClass));

        while (!queue.isEmpty() && queue.size() < 100) { // Prevent infinite loops
            List<OWLClass> currentPath = queue.poll();
            OWLClass currentClass = currentPath.get(currentPath.size() - 1);

            if (visited.contains(currentClass)) continue;
            visited.add(currentClass);

            // Check if we can reach target directly
            OWLSubClassOfAxiom directSub = dataFactory.getOWLSubClassOfAxiom(currentClass, targetClass);
            if (ontology.containsAxiom(directSub)) {
                List<OWLClass> completePath = new ArrayList<>(currentPath);
                completePath.add(targetClass);
                return completePath;
            }

            // Continue searching through superclasses
            Set<OWLSubClassOfAxiom> superAxioms = ontology.getSubClassAxiomsForSubClass(currentClass);
            for (OWLSubClassOfAxiom superAxiom : superAxioms) {
                OWLClassExpression superExpr = superAxiom.getSuperClass();
                if (!superExpr.isAnonymous()) {
                    OWLClass superClass = superExpr.asOWLClass();
                    if (!visited.contains(superClass) && !superClass.isOWLThing() && currentPath.size() < 10) {
                        List<OWLClass> newPath = new ArrayList<>(currentPath);
                        newPath.add(superClass);
                        queue.add(newPath);
                    }
                }
            }
        }

        return new ArrayList<>(); // No path found
    }

    /**
     * Remove duplicate explanation paths based on their logical content
     */
    private Set<ExplanationPath> deduplicatePaths(Set<ExplanationPath> allPaths) {
        Map<String, ExplanationPath> uniquePaths = new HashMap<>();

        for (ExplanationPath path : allPaths) {
            String signature = createPathSignature(path);

            // If we already have this signature, keep the one with better description
            if (uniquePaths.containsKey(signature)) {
                ExplanationPath existing = uniquePaths.get(signature);

                // Prefer paths with more detailed descriptions or more axioms
                if (path.getDescription().length() > existing.getDescription().length() ||
                        (path.getDescription().length() == existing.getDescription().length() &&
                                path.getAxioms().size() > existing.getAxioms().size())) {
                    uniquePaths.put(signature, path);
                }
            } else {
                uniquePaths.put(signature, path);
            }
        }

        LOGGER.debug("Deduplicated {} paths down to {} unique paths",
                allPaths.size(), uniquePaths.size());

        return new HashSet<>(uniquePaths.values());
    }

    /**
     * Create a normalized signature for a path to detect duplicates
     */
    private String createPathSignature(ExplanationPath path) {
        StringBuilder signature = new StringBuilder();

        // Add explanation type
        signature.append(path.getType().toString()).append("|");

        // Add normalized axioms (sorted for consistency)
        List<String> axiomStrings = path.getAxioms().stream()
                .map(this::normalizeAxiom)
                .sorted()
                .collect(Collectors.toList());

        signature.append(String.join(";", axiomStrings));

        return signature.toString();
    }

    /**
     * Normalize an axiom to a standard format for comparison
     */
    private String normalizeAxiom(OWLAxiom axiom) {
        if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom opa = (OWLObjectPropertyAssertionAxiom) axiom;
            return "PROP:" + getShortForm(opa.getSubject()) + "|" +
                    getShortForm(opa.getProperty()) + "|" + getShortForm(opa.getObject());
        }

        if (axiom instanceof OWLObjectPropertyDomainAxiom) {
            OWLObjectPropertyDomainAxiom opda = (OWLObjectPropertyDomainAxiom) axiom;
            return "DOMAIN:" + getShortForm(opda.getProperty()) + "|" + getShortForm(opda.getDomain());
        }

        if (axiom instanceof OWLObjectPropertyRangeAxiom) {
            OWLObjectPropertyRangeAxiom opra = (OWLObjectPropertyRangeAxiom) axiom;
            return "RANGE:" + getShortForm(opra.getProperty()) + "|" + getShortForm(opra.getRange());
        }

        if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom ca = (OWLClassAssertionAxiom) axiom;
            return "CLASS:" + getShortForm(ca.getIndividual()) + "|" + getShortForm(ca.getClassExpression());
        }

        if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom sca = (OWLSubClassOfAxiom) axiom;
            return "SUBCLASS:" + getShortForm(sca.getSubClass()) + "|" + getShortForm(sca.getSuperClass());
        }

        if (axiom instanceof OWLEquivalentClassesAxiom) {
            OWLEquivalentClassesAxiom eca = (OWLEquivalentClassesAxiom) axiom;
            List<String> classes = eca.getClassExpressions().stream()
                    .map(this::getShortForm)
                    .sorted()
                    .collect(Collectors.toList());
            return "EQUIV:" + String.join("|", classes);
        }

        // For other axiom types, use a generic approach
        return "OTHER:" + axiom.getAxiomType().toString() + "|" + axiom.toString();
    }

    /**
     * MAIN METHOD: Find ALL explanation paths using systematic approach
     */
    public Set<ExplanationPath> findAllExplanationPaths(OWLNamedIndividual individual, OWLClass clazz) {
        Set<ExplanationPath> allPaths = new HashSet<>();

        try {
            LOGGER.debug("Finding ALL explanation paths for {} : {}", getShortForm(individual), getShortForm(clazz));

            // Strategy 1: Use systematic axiom visitor approach (PRIMARY)
            addAxiomVisitorPaths(individual, clazz, allPaths);

            // Strategy 2: Use reasoner-based approaches
            addReasonerBasedPaths(individual, clazz, allPaths);

            // Strategy 3: Direct class assertions
            addDirectClassAssertionPaths(individual, clazz, allPaths);

            // All other existing strategies...
            addAllHierarchicalPaths(individual, clazz, allPaths);
            addEquivalentClassPaths(individual, clazz, allPaths);
            addPropertyBasedClassPaths(individual, clazz, allPaths);
            addComplexRestrictionPaths(individual, clazz, allPaths);
            addPropertyChainClassPaths(individual, clazz, allPaths);
            addInversePropertyClassPaths(individual, clazz, allPaths);
            addFunctionalPropertyClassPaths(individual, clazz, allPaths);
            addSymmetricPropertyClassPaths(individual, clazz, allPaths);
            addSubPropertyClassPaths(individual, clazz, allPaths);
            addRangeBasedClassPaths(individual, clazz, allPaths);

            LOGGER.info("Found {} RAW explanation paths for {} : {}",
                    allPaths.size(), getShortForm(individual), getShortForm(clazz));

            // DEDUPLICATE PATHS
            allPaths = deduplicatePaths(allPaths);

            LOGGER.info("Found {} UNIQUE explanation paths for {} : {}",
                    allPaths.size(), getShortForm(individual), getShortForm(clazz));

            // Log each unique path for debugging
            int pathNum = 1;
            for (ExplanationPath path : allPaths) {
                String tag = tagger.tagExplanation(path);
                LOGGER.debug("  Path {}: {} (complexity: {}, tag: {})",
                        pathNum++, path.getDescription(), path.getComplexity(), tag);
            }

        } catch (Exception e) {
            LOGGER.error("Error finding explanation paths for {} : {}", individual, clazz, e);
        }

        return allPaths;
    }

    /**
     * Enhanced explanation finding that traces back to asserted facts
     */
    public Set<ExplanationPath> findExplanationPathsLikeProtege(OWLNamedIndividual individual, OWLClass clazz) {
        Set<ExplanationPath> allPaths = new HashSet<>();

        try {
            LOGGER.debug("Finding explanations for INFERRED: {} : {}", getShortForm(individual), getShortForm(clazz));

            // Strategy 1: Find paths that trace back to ASSERTED facts
            addAssertedFactTracePaths(individual, clazz, allPaths);

            // Strategy 2: Standard comprehensive strategies as fallback
            Set<ExplanationPath> standardPaths = findAllExplanationPaths(individual, clazz);
            allPaths.addAll(standardPaths);

            // Deduplicate
            allPaths = deduplicatePaths(allPaths);

            LOGGER.debug("Found {} explanation paths for INFERRED {} : {} (traced to asserted facts)",
                    allPaths.size(), getShortForm(individual), getShortForm(clazz));

        } catch (Exception e) {
            LOGGER.error("Error finding explanation paths", e);
        }

        return allPaths;
    }

    /**
     * NEW: Find ALL hierarchical reasoning paths (comprehensive subclass exploration)
     */
    private void addAllHierarchicalPaths(OWLNamedIndividual individual, OWLClass targetClass, Set<ExplanationPath> allPaths) {
        try {
            // Get ALL classes the individual is asserted to belong to
            Set<OWLClass> directTypes = ontology.getClassAssertionAxioms(individual).stream()
                    .map(ax -> ax.getClassExpression())
                    .filter(expr -> !expr.isAnonymous())
                    .map(expr -> expr.asOWLClass())
                    .collect(Collectors.toSet());

            // Also get inferred types from reasoner
            Set<OWLClass> inferredTypes = reasoner.getTypes(individual, false).getFlattened();

            Set<OWLClass> allTypes = new HashSet<>();
            allTypes.addAll(directTypes);
            allTypes.addAll(inferredTypes);

            LOGGER.debug("Found {} total types for {}: {}", allTypes.size(), getShortForm(individual),
                    allTypes.stream().map(this::getShortForm).collect(Collectors.toList()));

            // For each type, find ALL possible paths to target class
            for (OWLClass sourceType : allTypes) {
                if (!sourceType.equals(targetClass) && !sourceType.isOWLThing()) {
                    // Use breadth-first search to find ALL paths
                    findAllPathsBFS(sourceType, targetClass, individual, allPaths);
                }
            }

        } catch (Exception e) {
            LOGGER.debug("Error finding all hierarchical paths: {}", e.getMessage());
        }
    }

    /**
     * NEW: Breadth-first search to find ALL paths between classes
     */
    private void findAllPathsBFS(OWLClass startClass, OWLClass targetClass, OWLNamedIndividual individual, Set<ExplanationPath> allPaths) {
        Queue<List<OWLClass>> queue = new LinkedList<>();
        Set<OWLClass> visited = new HashSet<>();

        queue.add(Arrays.asList(startClass));

        while (!queue.isEmpty() && queue.size() < 1000) { // Prevent infinite loops
            List<OWLClass> currentPath = queue.poll();
            OWLClass currentClass = currentPath.get(currentPath.size() - 1);

            if (visited.contains(currentClass)) continue;
            visited.add(currentClass);

            // Check direct subclass relationship to target
            OWLSubClassOfAxiom directSub = dataFactory.getOWLSubClassOfAxiom(currentClass, targetClass);
            if (ontology.containsAxiom(directSub)) {
                // Found a path!
                createHierarchicalPath(individual, currentPath, targetClass, allPaths);
            }

            // Continue searching through superclasses
            Set<OWLSubClassOfAxiom> superAxioms = ontology.getSubClassAxiomsForSubClass(currentClass);
            for (OWLSubClassOfAxiom superAxiom : superAxioms) {
                OWLClassExpression superExpr = superAxiom.getSuperClass();
                if (!superExpr.isAnonymous()) {
                    OWLClass superClass = superExpr.asOWLClass();
                    if (!visited.contains(superClass) && !superClass.isOWLThing() && currentPath.size() < 10) {
                        List<OWLClass> newPath = new ArrayList<>(currentPath);
                        newPath.add(superClass);
                        queue.add(newPath);
                    }
                }
            }

            // Also check equivalent classes
            for (OWLEquivalentClassesAxiom equivAxiom : ontology.getEquivalentClassesAxioms(currentClass)) {
                for (OWLClassExpression expr : equivAxiom.getClassExpressions()) {
                    if (!expr.equals(currentClass) && !expr.isAnonymous()) {
                        OWLClass equivClass = expr.asOWLClass();
                        if (!visited.contains(equivClass) && currentPath.size() < 10) {
                            List<OWLClass> newPath = new ArrayList<>(currentPath);
                            newPath.add(equivClass);
                            queue.add(newPath);
                        }
                    }
                }
            }
        }
    }

    /**
     * Create hierarchical path from class sequence
     */
    private void createHierarchicalPath(OWLNamedIndividual individual, List<OWLClass> path, OWLClass targetClass, Set<ExplanationPath> allPaths) {
        if (path.isEmpty()) return;

        List<String> justifications = new ArrayList<>();
        List<OWLAxiom> axioms = new ArrayList<>();

        // Initial assertion for the individual
        OWLClass startClass = path.get(0);
        OWLClassAssertionAxiom initialAssertion = dataFactory.getOWLClassAssertionAxiom(startClass, individual);
        axioms.add(initialAssertion);
        justifications.add(getShortForm(individual) + " rdf:type " + getShortForm(startClass));

        // Add all intermediate steps
        for (int i = 0; i < path.size() - 1; i++) {
            OWLClass fromClass = path.get(i);
            OWLClass toClass = path.get(i + 1);

            // Check for subclass axiom
            OWLSubClassOfAxiom stepAxiom = dataFactory.getOWLSubClassOfAxiom(fromClass, toClass);
            if (ontology.containsAxiom(stepAxiom)) {
                axioms.add(stepAxiom);
                justifications.add(getShortForm(fromClass) + " SubClassOf " + getShortForm(toClass));
            }

            // Check for equivalent class axiom
            for (OWLEquivalentClassesAxiom equivAxiom : ontology.getEquivalentClassesAxioms(fromClass)) {
                if (equivAxiom.getClassExpressions().contains(toClass)) {
                    axioms.add(equivAxiom);
                    justifications.add(getShortForm(fromClass) + " EquivalentTo " + getShortForm(toClass));
                    break;
                }
            }
        }

        // Final step to target
        OWLClass lastClass = path.get(path.size() - 1);
        OWLSubClassOfAxiom finalAxiom = dataFactory.getOWLSubClassOfAxiom(lastClass, targetClass);
        if (ontology.containsAxiom(finalAxiom)) {
            axioms.add(finalAxiom);
            justifications.add(getShortForm(lastClass) + " SubClassOf " + getShortForm(targetClass));
        }

        if (axioms.size() > 1) { // Only add if we have real reasoning steps
            ExplanationPath hierarchicalPath = new ExplanationPath(
                    axioms,
                    String.format("Hierarchical path (%d steps): %s â†’ %s",
                            path.size(), getShortForm(startClass), getShortForm(targetClass)),
                    ExplanationType.SUBSUMPTION,
                    axioms.size()
            );
            hierarchicalPath.setInferred(true);
            hierarchicalPath.setJustifications(justifications);
            allPaths.add(hierarchicalPath);

            LOGGER.debug("Added hierarchical path: {} steps, {} axioms", path.size(), axioms.size());
        }
    }

    private String formatAxiomForJustification(OWLAxiom axiom) {
        return OntologyUtils.formatAxiom(axiom);
    }

    /**
     * Property assertion methods (your existing method)
     */
    public Set<ExplanationPath> findPropertyAssertionPaths(OWLNamedIndividual subject,
                                                           OWLObjectProperty property,
                                                           OWLNamedIndividual object) {
        Set<ExplanationPath> allPaths = new HashSet<>();

        try {
            LOGGER.debug("Finding property assertion paths for {} {} {}",
                    getShortForm(subject), getShortForm(property), getShortForm(object));

            // Add direct property assertion paths
            addDirectPropertyAssertionPaths(subject, property, object, allPaths);

            // Add other property reasoning paths
            addSubPropertyReasoningPaths(subject, property, object, allPaths);
            addPropertyCharacteristicPaths(subject, property, object, allPaths);
            addPropertyChainReasoningPaths(subject, property, object, allPaths);

            LOGGER.debug("Found {} property assertion paths for {} {} {}",
                    allPaths.size(), getShortForm(subject), getShortForm(property), getShortForm(object));

        } catch (Exception e) {
            LOGGER.debug("Error finding property assertion paths for {} {} {}",
                    getShortForm(subject), getShortForm(property), getShortForm(object), e);
        }

        return allPaths;
    }

    /**
     * Complete ExplanationAxiomVisitor that systematically analyzes all axiom types
     */
    private class ExplanationAxiomVisitor implements OWLAxiomVisitor {
        private final OWLNamedIndividual individual;
        private final OWLClass targetClass;
        private final Set<ExplanationPath> paths;

        public ExplanationAxiomVisitor(OWLNamedIndividual individual, OWLClass targetClass, Set<ExplanationPath> paths) {
            this.individual = individual;
            this.targetClass = targetClass;
            this.paths = paths;
        }

        @Override
        public void visit(OWLSubClassOfAxiom axiom) {
            // Check if this subclass axiom contributes to the target class
            if (axiom.getSuperClass().equals(targetClass)) {
                OWLClassExpression subClass = axiom.getSubClass();
                if (!subClass.isAnonymous()) {
                    OWLClass subClazz = subClass.asOWLClass();
                    if (reasoner.getTypes(individual, false).getFlattened().contains(subClazz)) {
                        createSubclassExplanationPath(subClazz, targetClass, axiom);
                    }
                }
            }
        }

        @Override
        public void visit(OWLEquivalentClassesAxiom axiom) {
            if (axiom.getClassExpressions().contains(targetClass)) {
                for (OWLClassExpression expr : axiom.getClassExpressions()) {
                    if (!expr.equals(targetClass) && !expr.isAnonymous()) {
                        OWLClass equivClass = expr.asOWLClass();
                        if (reasoner.getTypes(individual, false).getFlattened().contains(equivClass)) {
                            createEquivalentClassExplanationPath(equivClass, targetClass, axiom);
                        }
                    }
                }
            }
        }

        @Override
        public void visit(OWLObjectPropertyDomainAxiom axiom) {
            if (axiom.getDomain().equals(targetClass)) {
                OWLObjectProperty property = axiom.getProperty().asOWLObjectProperty();
                Set<OWLNamedIndividual> values = reasoner.getObjectPropertyValues(individual, property).getFlattened();
                if (!values.isEmpty()) {
                    createDomainExplanationPath(property, targetClass, axiom, values.iterator().next());
                }
            }
        }

        @Override
        public void visit(OWLObjectPropertyRangeAxiom axiom) {
            if (axiom.getRange().equals(targetClass)) {
                OWLObjectProperty property = axiom.getProperty().asOWLObjectProperty();
                // Find who has this property pointing to our individual
                for (OWLNamedIndividual subject : ontology.getIndividualsInSignature()) {
                    if (reasoner.getObjectPropertyValues(subject, property).getFlattened().contains(individual)) {
                        createRangeExplanationPath(property, targetClass, axiom, subject);
                        break;
                    }
                }
            }
        }

        // Helper methods to create explanation paths
        private void createSubclassExplanationPath(OWLClass subClass, OWLClass targetClass, OWLAxiom axiom) {
            List<OWLAxiom> axioms = Arrays.asList(
                    dataFactory.getOWLClassAssertionAxiom(subClass, individual),
                    axiom
            );

            List<String> justifications = Arrays.asList(
                    getShortForm(individual) + " rdf:type " + getShortForm(subClass),
                    getShortForm(subClass) + " SubClassOf " + getShortForm(targetClass)
            );

            ExplanationPath path = new ExplanationPath(
                    axioms,
                    String.format("Subclass reasoning: %s â†’ %s", getShortForm(subClass), getShortForm(targetClass)),
                    ExplanationType.SUBSUMPTION,
                    axioms.size()
            );
            path.setJustifications(justifications);
            path.setInferred(true);
            paths.add(path);

            LOGGER.debug("Axiom visitor added subclass path: {} â†’ {}", getShortForm(subClass), getShortForm(targetClass));
        }

        private void createEquivalentClassExplanationPath(OWLClass equivClass, OWLClass targetClass, OWLAxiom axiom) {
            List<OWLAxiom> axioms = Arrays.asList(
                    dataFactory.getOWLClassAssertionAxiom(equivClass, individual),
                    axiom
            );

            List<String> justifications = Arrays.asList(
                    getShortForm(individual) + " rdf:type " + getShortForm(equivClass),
                    getShortForm(equivClass) + " EquivalentTo " + getShortForm(targetClass)
            );

            ExplanationPath path = new ExplanationPath(
                    axioms,
                    String.format("Equivalent class: %s â‰¡ %s", getShortForm(equivClass), getShortForm(targetClass)),
                    ExplanationType.EQUIVALENT_CLASS,
                    axioms.size()
            );
            path.setJustifications(justifications);
            path.setInferred(true);
            paths.add(path);

            LOGGER.debug("Axiom visitor added equivalent class path: {} â‰¡ {}", getShortForm(equivClass), getShortForm(targetClass));
        }

        private void createDomainExplanationPath(OWLObjectProperty property, OWLClass targetClass, OWLAxiom axiom, OWLNamedIndividual value) {
            List<OWLAxiom> axioms = Arrays.asList(
                    dataFactory.getOWLObjectPropertyAssertionAxiom(property, individual, value),
                    axiom
            );

            List<String> justifications = Arrays.asList(
                    getShortForm(individual) + " " + getShortForm(property) + " " + getShortForm(value),
                    getShortForm(property) + " Domain " + getShortForm(targetClass)
            );

            ExplanationPath path = new ExplanationPath(
                    axioms,
                    String.format("Domain reasoning: domain(%s) = %s", getShortForm(property), getShortForm(targetClass)),
                    ExplanationType.DOMAIN_RANGE,
                    axioms.size()
            );
            path.setJustifications(justifications);
            path.setInferred(true);
            paths.add(path);

            LOGGER.debug("Axiom visitor added domain path: domain({}) = {}", getShortForm(property), getShortForm(targetClass));
        }

        private void createRangeExplanationPath(OWLObjectProperty property, OWLClass targetClass, OWLAxiom axiom, OWLNamedIndividual subject) {
            List<OWLAxiom> axioms = Arrays.asList(
                    dataFactory.getOWLObjectPropertyAssertionAxiom(property, subject, individual),
                    axiom
            );

            List<String> justifications = Arrays.asList(
                    getShortForm(subject) + " " + getShortForm(property) + " " + getShortForm(individual),
                    getShortForm(property) + " Range " + getShortForm(targetClass)
            );

            ExplanationPath path = new ExplanationPath(
                    axioms,
                    String.format("Range reasoning: range(%s) = %s", getShortForm(property), getShortForm(targetClass)),
                    ExplanationType.DOMAIN_RANGE,
                    axioms.size()
            );
            path.setJustifications(justifications);
            path.setInferred(true);
            paths.add(path);

            LOGGER.debug("Axiom visitor added range path: range({}) = {}", getShortForm(property), getShortForm(targetClass));
        }

        // Empty implementations for remaining required methods
        @Override
        public void visit(OWLFunctionalObjectPropertyAxiom axiom) {
        }

        @Override
        public void visit(OWLInverseObjectPropertiesAxiom axiom) {
        }

        @Override
        public void visit(OWLSymmetricObjectPropertyAxiom axiom) {
        }

        @Override
        public void visit(OWLTransitiveObjectPropertyAxiom axiom) {
        }

        @Override
        public void visit(OWLSubObjectPropertyOfAxiom axiom) {
        }

        @Override
        public void visit(OWLSubPropertyChainOfAxiom axiom) {
        }

        @Override
        public void visit(OWLClassAssertionAxiom axiom) {
        }

        @Override
        public void visit(OWLObjectPropertyAssertionAxiom axiom) {
        }

        @Override
        public void visit(OWLDataPropertyAssertionAxiom axiom) {
        }

        @Override
        public void visit(OWLNegativeObjectPropertyAssertionAxiom axiom) {
        }

        @Override
        public void visit(OWLNegativeDataPropertyAssertionAxiom axiom) {
        }

        @Override
        public void visit(OWLSameIndividualAxiom axiom) {
        }

        @Override
        public void visit(OWLDifferentIndividualsAxiom axiom) {
        }

        @Override
        public void visit(OWLDisjointClassesAxiom axiom) {
        }

        @Override
        public void visit(OWLDisjointUnionAxiom axiom) {
        }

        @Override
        public void visit(OWLDeclarationAxiom axiom) {
        }

        @Override
        public void visit(OWLAnnotationAssertionAxiom axiom) {
        }

        @Override
        public void visit(OWLSubAnnotationPropertyOfAxiom axiom) {
        }

        @Override
        public void visit(OWLAnnotationPropertyDomainAxiom axiom) {
        }

        @Override
        public void visit(OWLAnnotationPropertyRangeAxiom axiom) {
        }

        @Override
        public void visit(OWLSubDataPropertyOfAxiom axiom) {
        }

        @Override
        public void visit(OWLEquivalentDataPropertiesAxiom axiom) {
        }

        @Override
        public void visit(OWLDisjointDataPropertiesAxiom axiom) {
        }

        @Override
        public void visit(OWLDataPropertyDomainAxiom axiom) {
        }

        @Override
        public void visit(OWLDataPropertyRangeAxiom axiom) {
        }

        @Override
        public void visit(OWLFunctionalDataPropertyAxiom axiom) {
        }

        @Override
        public void visit(OWLEquivalentObjectPropertiesAxiom axiom) {
        }

        @Override
        public void visit(OWLDisjointObjectPropertiesAxiom axiom) {
        }

        @Override
        public void visit(OWLInverseFunctionalObjectPropertyAxiom axiom) {
        }

        @Override
        public void visit(OWLIrreflexiveObjectPropertyAxiom axiom) {
        }

        @Override
        public void visit(OWLReflexiveObjectPropertyAxiom axiom) {
        }

        @Override
        public void visit(OWLAsymmetricObjectPropertyAxiom axiom) {
        }

        @Override
        public void visit(OWLDatatypeDefinitionAxiom axiom) {
        }

        @Override
        public void visit(OWLHasKeyAxiom axiom) {
        }

        @Override
        public void visit(SWRLRule node) {
        }
    }


    /**
     * Add axiom visitor paths using the systematic visitor approach
     */
    private void addAxiomVisitorPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            LOGGER.debug("Using axiom visitor to find additional paths");

            ExplanationAxiomVisitor visitor = new ExplanationAxiomVisitor(individual, clazz, allPaths);

            int axiomCount = 0;
            for (OWLAxiom axiom : ontology.getAxioms()) {
                axiom.accept(visitor);
                axiomCount++;
            }

            LOGGER.debug("Axiom visitor processed {} axioms", axiomCount);

        } catch (Exception e) {
            LOGGER.debug("Error in axiom visitor approach: {}", e.getMessage());
        }
    }

    /**
     * NEW: Find property chain reasoning paths leading to class membership
     */
    private void addPropertyChainClassPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // Look for domain axioms that could be triggered by property chains
            for (OWLObjectPropertyDomainAxiom domainAxiom : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_DOMAIN)) {
                if (domainAxiom.getDomain().equals(clazz)) {
                    OWLObjectProperty domainProp = domainAxiom.getProperty().asOWLObjectProperty();

                    // Check if this property is the result of a property chain
                    for (OWLSubPropertyChainOfAxiom chainAxiom : ontology.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF)) {
                        if (chainAxiom.getSuperProperty().equals(domainProp)) {
                            List<OWLObjectPropertyExpression> chain = chainAxiom.getPropertyChain();

                            // Find instances where this chain applies
                            if (chain.size() == 2) {
                                OWLObjectProperty prop1 = chain.get(0).asOWLObjectProperty();
                                OWLObjectProperty prop2 = chain.get(1).asOWLObjectProperty();

                                Set<OWLNamedIndividual> intermediates = reasoner.getObjectPropertyValues(individual, prop1).getFlattened();
                                for (OWLNamedIndividual intermediate : intermediates) {
                                    Set<OWLNamedIndividual> targets = reasoner.getObjectPropertyValues(intermediate, prop2).getFlattened();
                                    if (!targets.isEmpty()) {
                                        // Found a property chain path!
                                        List<OWLAxiom> axioms = Arrays.asList(
                                                dataFactory.getOWLObjectPropertyAssertionAxiom(prop1, individual, intermediate),
                                                dataFactory.getOWLObjectPropertyAssertionAxiom(prop2, intermediate, targets.iterator().next()),
                                                chainAxiom,
                                                domainAxiom
                                        );

                                        List<String> justifications = Arrays.asList(
                                                getShortForm(individual) + " " + getShortForm(prop1) + " " + getShortForm(intermediate),
                                                getShortForm(intermediate) + " " + getShortForm(prop2) + " " + getShortForm(targets.iterator().next()),
                                                getShortForm(prop1) + " âˆ˜ " + getShortForm(prop2) + " SubPropertyOf " + getShortForm(domainProp),
                                                getShortForm(domainProp) + " Domain " + getShortForm(clazz)
                                        );

                                        ExplanationPath path = new ExplanationPath(
                                                axioms,
                                                String.format("Property chain reasoning: %s âˆ˜ %s â†’ %s domain",
                                                        getShortForm(prop1), getShortForm(prop2), getShortForm(domainProp)),
                                                ExplanationType.PROPERTY_CHAIN,
                                                axioms.size()
                                        );
                                        path.setInferred(true);
                                        path.setJustifications(justifications);
                                        allPaths.add(path);

                                        LOGGER.debug("Added property chain path: {} âˆ˜ {} â†’ {} domain",
                                                getShortForm(prop1), getShortForm(prop2), getShortForm(domainProp));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in property chain class reasoning: {}", e.getMessage());
        }
    }

    /**
     * NEW: Find inverse property reasoning paths
     */
    private void addInversePropertyClassPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // Look for domain/range axioms that could be triggered by inverse properties
            for (OWLObjectPropertyDomainAxiom domainAxiom : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_DOMAIN)) {
                if (domainAxiom.getDomain().equals(clazz)) {
                    OWLObjectProperty domainProp = domainAxiom.getProperty().asOWLObjectProperty();

                    // Check if this property has an inverse
                    for (OWLInverseObjectPropertiesAxiom invAxiom : ontology.getInverseObjectPropertyAxioms(domainProp)) {
                        for (OWLObjectPropertyExpression invPropExpr : invAxiom.getProperties()) {
                            if (!invPropExpr.equals(domainProp) && !invPropExpr.isAnonymous()) {
                                OWLObjectProperty invProp = invPropExpr.asOWLObjectProperty();

                                // Check if someone has the inverse property pointing to our individual
                                for (OWLNamedIndividual other : ontology.getIndividualsInSignature()) {
                                    if (reasoner.getObjectPropertyValues(other, invProp).getFlattened().contains(individual)) {
                                        List<OWLAxiom> axioms = Arrays.asList(
                                                dataFactory.getOWLObjectPropertyAssertionAxiom(invProp, other, individual),
                                                invAxiom,
                                                domainAxiom
                                        );

                                        List<String> justifications = Arrays.asList(
                                                getShortForm(other) + " " + getShortForm(invProp) + " " + getShortForm(individual),
                                                getShortForm(domainProp) + " InverseOf " + getShortForm(invProp),
                                                getShortForm(domainProp) + " Domain " + getShortForm(clazz)
                                        );

                                        ExplanationPath path = new ExplanationPath(
                                                axioms,
                                                String.format("Inverse property reasoning: %s via %s",
                                                        getShortForm(domainProp), getShortForm(invProp)),
                                                ExplanationType.INVERSE_PROPERTY,
                                                axioms.size()
                                        );
                                        path.setInferred(true);
                                        path.setJustifications(justifications);
                                        allPaths.add(path);

                                        LOGGER.debug("Added inverse property path: {} via {}",
                                                getShortForm(domainProp), getShortForm(invProp));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in inverse property class reasoning: {}", e.getMessage());
        }
    }

    /**
     * NEW: Find functional property reasoning paths
     */
    private void addFunctionalPropertyClassPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // Look for functional properties that could lead to class membership
            for (OWLFunctionalObjectPropertyAxiom funcAxiom : ontology.getAxioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY)) {
                OWLObjectProperty funcProp = funcAxiom.getProperty().asOWLObjectProperty();

                // Check if this functional property has a range that relates to our target class
                for (OWLObjectPropertyRangeAxiom rangeAxiom : ontology.getObjectPropertyRangeAxioms(funcProp)) {
                    if (rangeAxiom.getRange().equals(clazz)) {
                        Set<OWLNamedIndividual> values = reasoner.getObjectPropertyValues(individual, funcProp).getFlattened();

                        if (!values.isEmpty()) {
                            // Since it's functional, there should be exactly one value
                            OWLNamedIndividual value = values.iterator().next();

                            List<OWLAxiom> axioms = Arrays.asList(
                                    dataFactory.getOWLObjectPropertyAssertionAxiom(funcProp, individual, value),
                                    funcAxiom,
                                    rangeAxiom
                            );

                            List<String> justifications = Arrays.asList(
                                    getShortForm(individual) + " " + getShortForm(funcProp) + " " + getShortForm(value),
                                    "Functional: " + getShortForm(funcProp),
                                    getShortForm(funcProp) + " Range " + getShortForm(clazz)
                            );

                            ExplanationPath path = new ExplanationPath(
                                    axioms,
                                    String.format("Functional property reasoning: %s range", getShortForm(funcProp)),
                                    ExplanationType.FUNCTIONAL_PROPERTY,
                                    axioms.size()
                            );
                            path.setInferred(true);
                            path.setJustifications(justifications);
                            allPaths.add(path);

                            LOGGER.debug("Added functional property path: {} range", getShortForm(funcProp));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in functional property class reasoning: {}", e.getMessage());
        }
    }

    /**
     * NEW: Find symmetric property reasoning paths
     */
    private void addSymmetricPropertyClassPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            for (OWLSymmetricObjectPropertyAxiom symAxiom : ontology.getAxioms(AxiomType.SYMMETRIC_OBJECT_PROPERTY)) {
                OWLObjectProperty symProp = symAxiom.getProperty().asOWLObjectProperty();

                // Check if this symmetric property has a domain that relates to our target class
                for (OWLObjectPropertyDomainAxiom domainAxiom : ontology.getObjectPropertyDomainAxioms(symProp)) {
                    if (domainAxiom.getDomain().equals(clazz)) {
                        // Check if someone else has this property pointing to our individual
                        for (OWLNamedIndividual other : ontology.getIndividualsInSignature()) {
                            if (!other.equals(individual) &&
                                    reasoner.getObjectPropertyValues(other, symProp).getFlattened().contains(individual)) {

                                List<OWLAxiom> axioms = Arrays.asList(
                                        dataFactory.getOWLObjectPropertyAssertionAxiom(symProp, other, individual),
                                        symAxiom,
                                        domainAxiom
                                );

                                List<String> justifications = Arrays.asList(
                                        getShortForm(other) + " " + getShortForm(symProp) + " " + getShortForm(individual),
                                        "Symmetric: " + getShortForm(symProp),
                                        getShortForm(symProp) + " Domain " + getShortForm(clazz)
                                );

                                ExplanationPath path = new ExplanationPath(
                                        axioms,
                                        String.format("Symmetric property reasoning: %s domain", getShortForm(symProp)),
                                        ExplanationType.SYMMETRIC_PROPERTY,
                                        axioms.size()
                                );
                                path.setInferred(true);
                                path.setJustifications(justifications);
                                allPaths.add(path);

                                LOGGER.debug("Added symmetric property path: {} domain", getShortForm(symProp));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in symmetric property class reasoning: {}", e.getMessage());
        }
    }

    /**
     * NEW: Find subproperty reasoning paths
     */
    private void addSubPropertyClassPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // Find all properties that have our individual as subject
            for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
                Set<OWLNamedIndividual> values = reasoner.getObjectPropertyValues(individual, property).getFlattened();

                if (!values.isEmpty()) {
                    // Check if this property is a subproperty of something with a domain
                    for (OWLSubObjectPropertyOfAxiom subPropAxiom : ontology.getObjectSubPropertyAxiomsForSubProperty(property)) {
                        OWLObjectProperty superProp = subPropAxiom.getSuperProperty().asOWLObjectProperty();

                        for (OWLObjectPropertyDomainAxiom domainAxiom : ontology.getObjectPropertyDomainAxioms(superProp)) {
                            if (domainAxiom.getDomain().equals(clazz)) {
                                OWLNamedIndividual value = values.iterator().next();

                                List<OWLAxiom> axioms = Arrays.asList(
                                        dataFactory.getOWLObjectPropertyAssertionAxiom(property, individual, value),
                                        subPropAxiom,
                                        domainAxiom
                                );

                                List<String> justifications = Arrays.asList(
                                        getShortForm(individual) + " " + getShortForm(property) + " " + getShortForm(value),
                                        getShortForm(property) + " SubPropertyOf " + getShortForm(superProp),
                                        getShortForm(superProp) + " Domain " + getShortForm(clazz)
                                );

                                ExplanationPath path = new ExplanationPath(
                                        axioms,
                                        String.format("Subproperty reasoning: %s â†’ %s domain",
                                                getShortForm(property), getShortForm(superProp)),
                                        ExplanationType.SUBPROPERTY,
                                        axioms.size()
                                );
                                path.setInferred(true);
                                path.setJustifications(justifications);
                                allPaths.add(path);

                                LOGGER.debug("Added subproperty path: {} â†’ {} domain",
                                        getShortForm(property), getShortForm(superProp));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in subproperty class reasoning: {}", e.getMessage());
        }
    }

    /**
     * NEW: Find range-based reasoning paths
     */
    private void addRangeBasedClassPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // Check if our individual is the object of some property that has a range restriction
            for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
                for (OWLObjectPropertyRangeAxiom rangeAxiom : ontology.getObjectPropertyRangeAxioms(property)) {
                    if (rangeAxiom.getRange().equals(clazz)) {
                        // Find who has this property pointing to our individual
                        for (OWLNamedIndividual subject : ontology.getIndividualsInSignature()) {
                            if (reasoner.getObjectPropertyValues(subject, property).getFlattened().contains(individual)) {
                                List<OWLAxiom> axioms = Arrays.asList(
                                        dataFactory.getOWLObjectPropertyAssertionAxiom(property, subject, individual),
                                        rangeAxiom
                                );

                                List<String> justifications = Arrays.asList(
                                        getShortForm(subject) + " " + getShortForm(property) + " " + getShortForm(individual),
                                        getShortForm(property) + " Range " + getShortForm(clazz)
                                );

                                ExplanationPath path = new ExplanationPath(
                                        axioms,
                                        String.format("Range reasoning: %s range", getShortForm(property)),
                                        ExplanationType.DOMAIN_RANGE,
                                        axioms.size()
                                );
                                path.setInferred(true);
                                path.setJustifications(justifications);
                                allPaths.add(path);

                                LOGGER.debug("Added range-based path: {} range", getShortForm(property));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in range-based class reasoning: {}", e.getMessage());
        }
    }

    /**
     * NEW: Find complex restriction combinations
     */
    private void addComplexRestrictionPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            // This would handle complex class expressions like:
            // Woman â‰¡ Person âŠ“ âˆƒhasSex.Female âŠ“ âˆ€hasChild.Person
            for (OWLEquivalentClassesAxiom equivAxiom : ontology.getEquivalentClassesAxioms(clazz)) {
                for (OWLClassExpression expr : equivAxiom.getClassExpressions()) {
                    if (expr instanceof OWLObjectIntersectionOf) {
                        OWLObjectIntersectionOf intersection = (OWLObjectIntersectionOf) expr;

                        List<OWLAxiom> axioms = new ArrayList<>();
                        List<String> justifications = new ArrayList<>();
                        axioms.add(equivAxiom);

                        boolean satisfiesAll = true;
                        int restrictionCount = 0;

                        for (OWLClassExpression component : intersection.getOperands()) {
                            if (component instanceof OWLClass) {
                                OWLClass componentClass = (OWLClass) component;
                                if (reasoner.getTypes(individual, false).getFlattened().contains(componentClass)) {
                                    OWLClassAssertionAxiom assertion = dataFactory.getOWLClassAssertionAxiom(componentClass, individual);
                                    axioms.add(assertion);
                                    justifications.add(getShortForm(individual) + " rdf:type " + getShortForm(componentClass));
                                    restrictionCount++;
                                } else {
                                    satisfiesAll = false;
                                    break;
                                }
                            } else if (component instanceof OWLObjectSomeValuesFrom) {
                                OWLObjectSomeValuesFrom someRestriction = (OWLObjectSomeValuesFrom) component;
                                if (!someRestriction.getProperty().isAnonymous()) {
                                    OWLObjectProperty prop = someRestriction.getProperty().asOWLObjectProperty();
                                    OWLClassExpression filler = someRestriction.getFiller();

                                    Set<OWLNamedIndividual> propValues = reasoner.getObjectPropertyValues(individual, prop).getFlattened();
                                    boolean foundWitness = false;

                                    for (OWLNamedIndividual value : propValues) {
                                        if (!filler.isAnonymous()) {
                                            OWLClass fillerClass = filler.asOWLClass();
                                            if (reasoner.getTypes(value, false).getFlattened().contains(fillerClass)) {
                                                axioms.add(dataFactory.getOWLObjectPropertyAssertionAxiom(prop, individual, value));
                                                axioms.add(dataFactory.getOWLClassAssertionAxiom(fillerClass, value));
                                                justifications.add(getShortForm(individual) + " " + getShortForm(prop) + " " + getShortForm(value));
                                                justifications.add(getShortForm(value) + " rdf:type " + getShortForm(fillerClass));
                                                foundWitness = true;
                                                restrictionCount++;
                                                break;
                                            }
                                        }
                                    }

                                    if (!foundWitness) {
                                        satisfiesAll = false;
                                        break;
                                    }
                                }
                            }
                        }

                        if (satisfiesAll && restrictionCount >= 2) {
                            justifications.add(String.format("Complex intersection satisfied (%d components)", restrictionCount));

                            ExplanationPath path = new ExplanationPath(
                                    axioms,
                                    String.format("Complex restriction combination (%d components)", restrictionCount),
                                    ExplanationType.INTERSECTION,
                                    axioms.size()
                            );
                            path.setInferred(true);
                            path.setJustifications(justifications);
                            allPaths.add(path);

                            LOGGER.debug("Added complex restriction path with {} components", restrictionCount);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in complex restriction reasoning: {}", e.getMessage());
        }
    }

    // Keep existing methods but remove Pellet getExplanation calls
    private void addDirectClassAssertionPaths(OWLNamedIndividual individual, OWLClass clazz, Set<ExplanationPath> allPaths) {
        try {
            OWLClassAssertionAxiom directAssertion = dataFactory.getOWLClassAssertionAxiom(clazz, individual);

            if (ontology.containsAxiom(directAssertion)) {
                List<String> justifications = Arrays.asList(
                        getShortForm(individual) + " rdf:type " + getShortForm(clazz) + " (direct assertion)"
                );

                ExplanationPath directPath = new ExplanationPath(
                        Arrays.asList(directAssertion),
                        "Direct class assertion found in ontology",
                        ExplanationType.DIRECT_ASSERTION,
                        1
                );
                directPath.setInferred(false);
                directPath.setJustifications(justifications);
                allPaths.add(directPath);

                LOGGER.debug("Added DIRECT assertion path for {} : {}",
                        getShortForm(individual), getShortForm(clazz));
            }
        } catch (Exception e) {
            LOGGER.debug("Error checking direct class assertion: {}", e.getMessage());
        }
    }

    /**
     * ENHANCED: Find ALL possible subclass reasoning paths
     */
    private void addSubclassReasoningPaths(OWLNamedIndividual individual, OWLClass targetClass, Set<ExplanationPath> allPaths) {
        try {
            // Get ALL direct class assertions for the individual (from ontology, not reasoner)
            Set<OWLClass> directTypes = ontology.getClassAssertionAxioms(individual).stream()
                    .map(ax -> ax.getClassExpression())
                    .filter(expr -> !expr.isAnonymous())
                    .map(expr -> expr.asOWLClass())
                    .collect(Collectors.toSet());

            LOGGER.debug("Found {} direct types for {}: {}", directTypes.size(), individual, directTypes);

            for (OWLClass directType : directTypes) {
                if (!directType.equals(targetClass) && !directType.isOWLThing()) {
                    // Find ALL possible subclass paths from directType to targetClass
                    findAllSubclassPaths(directType, targetClass, individual, allPaths, new ArrayList<>(), 0);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in subclass reasoning: {}", e.getMessage());
        }
    }

    /**
     * ENHANCED: Find ALL transitive subclass paths (not just direct ones)
     */
    private void findAllSubclassPaths(OWLClass startClass, OWLClass targetClass, OWLNamedIndividual individual,
                                      Set<ExplanationPath> allPaths, List<OWLClass> currentPath, int depth) {
        if (currentPath.contains(startClass) || depth > 10) { // Prevent cycles and excessive depth
            return;
        }

        List<OWLClass> newPath = new ArrayList<>(currentPath);
        newPath.add(startClass);

        try {
            // 1. Check for DIRECT subclass relationship
            OWLSubClassOfAxiom directSub = dataFactory.getOWLSubClassOfAxiom(startClass, targetClass);
            if (ontology.containsAxiom(directSub)) {
                createSubclassPath(individual, startClass, targetClass, newPath, allPaths, depth + 1);
            }

            // 2. Find TRANSITIVE paths through intermediate classes
            Set<OWLSubClassOfAxiom> subAxioms = ontology.getSubClassAxiomsForSubClass(startClass);
            for (OWLSubClassOfAxiom subAxiom : subAxioms) {
                OWLClassExpression superExpr = subAxiom.getSuperClass();
                if (!superExpr.isAnonymous()) {
                    OWLClass intermediate = superExpr.asOWLClass();
                    if (!intermediate.equals(targetClass) && !currentPath.contains(intermediate) && !intermediate.isOWLThing()) {
                        // Recursively search through intermediate class
                        findAllSubclassPaths(intermediate, targetClass, individual, allPaths, newPath, depth + 1);
                    }
                }
            }

            // 3. Check for equivalent classes that might lead to target
            Set<OWLEquivalentClassesAxiom> equivAxioms = ontology.getEquivalentClassesAxioms(startClass);
            for (OWLEquivalentClassesAxiom equivAxiom : equivAxioms) {
                for (OWLClassExpression equiv : equivAxiom.getClassExpressions()) {
                    if (!equiv.equals(startClass) && !equiv.isAnonymous()) {
                        OWLClass equivClass = equiv.asOWLClass();
                        if (!currentPath.contains(equivClass)) {
                            findAllSubclassPaths(equivClass, targetClass, individual, allPaths, newPath, depth + 1);
                        }
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.debug("Error finding subclass paths: {}", e.getMessage());
        }
    }


    private void createSubclassPath(OWLNamedIndividual individual, OWLClass startClass, OWLClass targetClass,
                                    List<OWLClass> path, Set<ExplanationPath> allPaths, int steps) {
        List<String> justifications = new ArrayList<>();
        List<OWLAxiom> axioms = new ArrayList<>();

        // Add the initial assertion
        OWLClassAssertionAxiom initialAssertion = dataFactory.getOWLClassAssertionAxiom(startClass, individual);
        axioms.add(initialAssertion);
        justifications.add(getShortForm(individual) + " rdf:type " + getShortForm(startClass));

        // Add all subclass steps
        for (int i = 0; i < path.size() - 1; i++) {
            OWLSubClassOfAxiom stepAxiom = dataFactory.getOWLSubClassOfAxiom(path.get(i), path.get(i + 1));
            if (ontology.containsAxiom(stepAxiom)) {
                axioms.add(stepAxiom);
                justifications.add(getShortForm(path.get(i)) + " rdfs:subClassOf " + getShortForm(path.get(i + 1)));
            }
        }

        // Add final step to target
        OWLSubClassOfAxiom finalStep = dataFactory.getOWLSubClassOfAxiom(startClass, targetClass);
        if (ontology.containsAxiom(finalStep)) {
            axioms.add(finalStep);
            justifications.add(getShortForm(startClass) + " rdfs:subClassOf " + getShortForm(targetClass));
        }

        ExplanationPath path_obj = new ExplanationPath(
                axioms,
                String.format("Subclass reasoning: %s âŠ‘ %s (%d steps)",
                        getShortForm(startClass), getShortForm(targetClass), steps),
                ExplanationType.SUBSUMPTION,
                axioms.size()
        );
        path_obj.setInferred(true);
        path_obj.setJustifications(justifications);
        allPaths.add(path_obj);

        LOGGER.debug("Added subclass path: {} -> {} ({} steps, {} axioms)",
                startClass, targetClass, steps, axioms.size());
    }

    /**
     * ENHANCED: Find ALL transitive subclass paths systematically
     */
    private void findAllTransitiveSubclassPaths(OWLNamedIndividual individual, OWLClass targetClass, Set<ExplanationPath> allPaths) {
        try {
            // Get all classes the individual belongs to (from ontology)
            Set<OWLClass> memberClasses = ontology.getClassAssertionAxioms(individual).stream()
                    .map(ax -> ax.getClassExpression())
                    .filter(expr -> !expr.isAnonymous())
                    .map(expr -> expr.asOWLClass())
                    .collect(Collectors.toSet());

            // For each member class, find ALL paths to target class
            for (OWLClass memberClass : memberClasses) {
                if (!memberClass.equals(targetClass) && !memberClass.isOWLThing()) {
                    findTransitivePaths(memberClass, targetClass, individual, allPaths, new HashSet<>(), new ArrayList<>(), 0);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error finding transitive subclass paths: {}", e.getMessage());
        }
    }

    private void findTransitivePaths(OWLClass currentClass, OWLClass targetClass, OWLNamedIndividual individual,
                                     Set<ExplanationPath> allPaths, Set<OWLClass> visited, List<OWLClass> path, int depth) {
        if (visited.contains(currentClass) || depth > 8) return;

        visited.add(currentClass);
        List<OWLClass> newPath = new ArrayList<>(path);
        newPath.add(currentClass);

        try {
            // Check all superclasses of currentClass
            Set<OWLSubClassOfAxiom> superAxioms = ontology.getSubClassAxiomsForSubClass(currentClass);

            for (OWLSubClassOfAxiom superAxiom : superAxioms) {
                OWLClassExpression superExpr = superAxiom.getSuperClass();

                if (!superExpr.isAnonymous()) {
                    OWLClass superClass = superExpr.asOWLClass();

                    if (superClass.equals(targetClass)) {
                        // Found a path to target!
                        createTransitivePath(individual, newPath, targetClass, allPaths);
                    } else if (!visited.contains(superClass) && !superClass.isOWLThing()) {
                        // Continue searching through this superclass
                        findTransitivePaths(superClass, targetClass, individual, allPaths,
                                new HashSet<>(visited), newPath, depth + 1);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in transitive path finding: {}", e.getMessage());
        }
    }

    private void createTransitivePath(OWLNamedIndividual individual, List<OWLClass> path, OWLClass targetClass,
                                      Set<ExplanationPath> allPaths) {
        if (path.isEmpty()) return;

        List<String> justifications = new ArrayList<>();
        List<OWLAxiom> axioms = new ArrayList<>();

        // Initial assertion
        OWLClass startClass = path.get(0);
        OWLClassAssertionAxiom initialAssertion = dataFactory.getOWLClassAssertionAxiom(startClass, individual);
        axioms.add(initialAssertion);
        justifications.add(getShortForm(individual) + " rdf:type " + getShortForm(startClass));

        // Add all intermediate steps
        for (int i = 0; i < path.size() - 1; i++) {
            OWLClass fromClass = path.get(i);
            OWLClass toClass = path.get(i + 1);
            OWLSubClassOfAxiom stepAxiom = dataFactory.getOWLSubClassOfAxiom(fromClass, toClass);

            if (ontology.containsAxiom(stepAxiom)) {
                axioms.add(stepAxiom);
                justifications.add(getShortForm(fromClass) + " rdfs:subClassOf " + getShortForm(toClass));
            }
        }

        // Final step to target
        OWLClass lastClass = path.get(path.size() - 1);
        OWLSubClassOfAxiom finalAxiom = dataFactory.getOWLSubClassOfAxiom(lastClass, targetClass);
        if (ontology.containsAxiom(finalAxiom)) {
            axioms.add(finalAxiom);
            justifications.add(getShortForm(lastClass) + " rdfs:subClassOf " + getShortForm(targetClass));
        }

        if (axioms.size() > 1) { // Only add if we have real reasoning steps
            ExplanationPath transPath = new ExplanationPath(
                    axioms,
                    String.format("Transitive subclass reasoning (%d steps): %s âŠ‘* %s",
                            path.size(), getShortForm(startClass), getShortForm(targetClass)),
                    ExplanationType.SUBSUMPTION,
                    axioms.size()
            );
            transPath.setInferred(true);
            transPath.setJustifications(justifications);
            allPaths.add(transPath);

            LOGGER.debug("Added transitive subclass path: {} steps, {} axioms", path.size(), axioms.size());
        }
    }

    /**
     * ENHANCED: Find restriction-based reasoning paths
     */
    private void findRestrictionBasedPaths(OWLNamedIndividual individual, OWLClass targetClass, Set<ExplanationPath> allPaths) {
        try {
            // Find all classes defined by restrictions that could lead to targetClass
            Set<OWLEquivalentClassesAxiom> equivAxioms = ontology.getEquivalentClassesAxioms(targetClass);

            for (OWLEquivalentClassesAxiom equivAxiom : equivAxioms) {
                for (OWLClassExpression expr : equivAxiom.getClassExpressions()) {
                    if (expr instanceof OWLObjectIntersectionOf) {
                        findIntersectionPaths(individual, (OWLObjectIntersectionOf) expr, equivAxiom, allPaths);
                    } else if (expr instanceof OWLObjectSomeValuesFrom) {
                        findExistentialPaths(individual, (OWLObjectSomeValuesFrom) expr, equivAxiom, allPaths);
                    } else if (expr instanceof OWLObjectAllValuesFrom) {
                        findUniversalPaths(individual, (OWLObjectAllValuesFrom) expr, equivAxiom, allPaths);
                    }
                }
            }

            // Also check subclass axioms for restrictions
            Set<OWLSubClassOfAxiom> subAxioms = ontology.getSubClassAxiomsForSuperClass(targetClass);
            for (OWLSubClassOfAxiom subAxiom : subAxioms) {
                OWLClassExpression subExpr = subAxiom.getSubClass();
                if (subExpr instanceof OWLObjectIntersectionOf) {
                    findIntersectionPaths(individual, (OWLObjectIntersectionOf) subExpr, subAxiom, allPaths);
                } else if (subExpr instanceof OWLObjectSomeValuesFrom) {
                    findExistentialPaths(individual, (OWLObjectSomeValuesFrom) subExpr, subAxiom, allPaths);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error finding restriction-based paths: {}", e.getMessage());
        }
    }

    /**
     * FIXED: Find intersection paths without infinite recursion
     */
    private void findIntersectionPaths(OWLNamedIndividual individual, OWLObjectIntersectionOf intersection,
                                       OWLAxiom containingAxiom, Set<ExplanationPath> allPaths) {
        try {
            List<OWLAxiom> axioms = new ArrayList<>();
            List<String> justifications = new ArrayList<>();
            axioms.add(containingAxiom);

            boolean satisfiesAll = true;
            int componentCount = 0;

            for (OWLClassExpression component : intersection.getOperands()) {
                if (!component.isAnonymous()) {
                    OWLClass componentClass = component.asOWLClass();

                    // Check if individual is a member of this component class
                    if (reasoner.getTypes(individual, false).getFlattened().contains(componentClass)) {
                        // Try to find how individual became member of component class
                        OWLClassAssertionAxiom componentAssertion = dataFactory.getOWLClassAssertionAxiom(componentClass, individual);

                        if (ontology.containsAxiom(componentAssertion)) {
                            // Direct assertion
                            axioms.add(componentAssertion);
                            justifications.add(getShortForm(individual) + " rdf:type " + getShortForm(componentClass));
                        }
                        // REMOVED: No recursive call to avoid infinite recursion

                        componentCount++;
                    } else {
                        satisfiesAll = false;
                        break;
                    }
                }
            }

            if (satisfiesAll && componentCount > 1 && axioms.size() > 1) {
                justifications.add("Intersection satisfied: member of all " + componentCount + " components");

                ExplanationPath intersectionPath = new ExplanationPath(
                        axioms,
                        String.format("Intersection class: member of all %d components", componentCount),
                        ExplanationType.INTERSECTION,
                        axioms.size()
                );
                intersectionPath.setInferred(true);
                intersectionPath.setJustifications(justifications);
                allPaths.add(intersectionPath);

                LOGGER.debug("Added intersection path with {} components, {} axioms", componentCount, axioms.size());
            }
        } catch (Exception e) {
            LOGGER.debug("Error in intersection reasoning: {}", e.getMessage());
        }
    }

    private void findExistentialPaths(OWLNamedIndividual individual, OWLObjectSomeValuesFrom restriction,
                                      OWLAxiom containingAxiom, Set<ExplanationPath> allPaths) {
        try {
            if (restriction.getProperty().isAnonymous()) return;

            OWLObjectProperty property = restriction.getProperty().asOWLObjectProperty();
            OWLClassExpression filler = restriction.getFiller();

            // Find all property values for this individual
            Set<OWLNamedIndividual> propertyValues = reasoner.getObjectPropertyValues(individual, property).getFlattened();

            for (OWLNamedIndividual value : propertyValues) {
                boolean satisfiesFiller = false;
                List<OWLAxiom> pathAxioms = new ArrayList<>();
                List<String> pathJustifications = new ArrayList<>();

                // Check if value satisfies the filler
                if (!filler.isAnonymous()) {
                    OWLClass fillerClass = filler.asOWLClass();
                    if (reasoner.getTypes(value, false).getFlattened().contains(fillerClass)) {
                        satisfiesFiller = true;

                        // Add the property assertion
                        OWLObjectPropertyAssertionAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(property, individual, value);
                        if (ontology.containsAxiom(propAssertion)) {
                            pathAxioms.add(propAssertion);
                            pathJustifications.add(getShortForm(individual) + " " + getShortForm(property) + " " + getShortForm(value));
                        }

                        // Add the type assertion for the value
                        OWLClassAssertionAxiom valueTypeAssertion = dataFactory.getOWLClassAssertionAxiom(fillerClass, value);
                        if (ontology.containsAxiom(valueTypeAssertion)) {
                            pathAxioms.add(valueTypeAssertion);
                            pathJustifications.add(getShortForm(value) + " rdf:type " + getShortForm(fillerClass));
                        }

                        pathAxioms.add(containingAxiom);
                        pathJustifications.add("âˆƒ" + getShortForm(property) + "." + getShortForm(fillerClass) + " restriction satisfied by " + getShortForm(value));
                    }
                }

                if (satisfiesFiller && pathAxioms.size() > 1) {
                    ExplanationPath existentialPath = new ExplanationPath(
                            pathAxioms,
                            String.format("Existential restriction: âˆƒ%s.%s satisfied by %s",
                                    getShortForm(property), getShortForm(filler), getShortForm(value)),
                            ExplanationType.EXISTENTIAL,
                            pathAxioms.size()
                    );
                    existentialPath.setInferred(true);
                    existentialPath.setJustifications(pathJustifications);
                    allPaths.add(existentialPath);

                    LOGGER.debug("Added existential restriction path with {} axioms", pathAxioms.size());
                    break; // One witness is sufficient
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in existential restriction reasoning: {}", e.getMessage());
        }
    }

    private void findUniversalPaths(OWLNamedIndividual individual, OWLObjectAllValuesFrom restriction,
                                    OWLAxiom containingAxiom, Set<ExplanationPath> allPaths) {
        try {
            if (restriction.getProperty().isAnonymous()) return;

            OWLObjectProperty property = restriction.getProperty().asOWLObjectProperty();
            OWLClassExpression filler = restriction.getFiller();

            Set<OWLNamedIndividual> propertyValues = reasoner.getObjectPropertyValues(individual, property).getFlattened();

            if (propertyValues.isEmpty()) {
                // Vacuous satisfaction - no property values, so universal restriction is satisfied
                List<String> justifications = Arrays.asList(
                        "No " + getShortForm(property) + " values for " + getShortForm(individual),
                        "âˆ€" + getShortForm(property) + "." + getShortForm(filler) + " vacuously satisfied"
                );

                ExplanationPath universalPath = new ExplanationPath(
                        Arrays.asList(containingAxiom),
                        String.format("Universal restriction: âˆ€%s.%s vacuously satisfied (no %s values)",
                                getShortForm(property), getShortForm(filler), getShortForm(property)),
                        ExplanationType.UNIVERSAL,
                        1
                );
                universalPath.setInferred(true);
                universalPath.setJustifications(justifications);
                allPaths.add(universalPath);

                LOGGER.debug("Added vacuous universal restriction path");
            } else {
                // Check if ALL values satisfy the filler
                boolean allSatisfy = true;
                List<OWLAxiom> pathAxioms = new ArrayList<>();
                List<String> pathJustifications = new ArrayList<>();

                for (OWLNamedIndividual value : propertyValues) {
                    if (!filler.isAnonymous()) {
                        OWLClass fillerClass = filler.asOWLClass();
                        if (!reasoner.getTypes(value, false).getFlattened().contains(fillerClass)) {
                            allSatisfy = false;
                            break;
                        } else {
                            // Add evidence that this value satisfies the restriction
                            OWLObjectPropertyAssertionAxiom propAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(property, individual, value);
                            OWLClassAssertionAxiom typeAssertion = dataFactory.getOWLClassAssertionAxiom(fillerClass, value);

                            if (ontology.containsAxiom(propAssertion) && ontology.containsAxiom(typeAssertion)) {
                                pathAxioms.add(propAssertion);
                                pathAxioms.add(typeAssertion);
                                pathJustifications.add(getShortForm(individual) + " " + getShortForm(property) + " " + getShortForm(value));
                                pathJustifications.add(getShortForm(value) + " rdf:type " + getShortForm(fillerClass));
                            }
                        }
                    }
                }

                if (allSatisfy && !pathAxioms.isEmpty()) {
                    pathAxioms.add(containingAxiom);
                    pathJustifications.add("âˆ€" + getShortForm(property) + "." + getShortForm(filler) + " satisfied by all " + propertyValues.size() + " values");

                    ExplanationPath universalPath = new ExplanationPath(
                            pathAxioms,
                            String.format("Universal restriction: âˆ€%s.%s satisfied by all %d values",
                                    getShortForm(property), getShortForm(filler), propertyValues.size()),
                            ExplanationType.UNIVERSAL,
                            pathAxioms.size()
                    );
                    universalPath.setInferred(true);
                    universalPath.setJustifications(pathJustifications);
                    allPaths.add(universalPath);

                    LOGGER.debug("Added universal restriction path with {} values, {} axioms", propertyValues.size(), pathAxioms.size());
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in universal restriction reasoning: {}", e.getMessage());
        }
    }

    private void addEquivalentClassPaths(OWLNamedIndividual individual, OWLClass targetClass, Set<ExplanationPath> allPaths) {
        try {
            for (OWLEquivalentClassesAxiom equivAxiom : ontology.getEquivalentClassesAxioms(targetClass)) {
                for (OWLClassExpression expr : equivAxiom.getClassExpressions()) {
                    if (!expr.equals(targetClass) && !expr.isAnonymous()) {
                        OWLClass equivClass = expr.asOWLClass();

                        // Check if individual is member of equivalent class
                        OWLClassAssertionAxiom membershipAxiom = dataFactory.getOWLClassAssertionAxiom(equivClass, individual);
                        if (ontology.containsAxiom(membershipAxiom) ||
                                reasoner.getTypes(individual, false).getFlattened().contains(equivClass)) {

                            List<String> justifications = Arrays.asList(
                                    getShortForm(individual) + " rdf:type " + getShortForm(equivClass),
                                    getShortForm(equivClass) + " owl:equivalentClass " + getShortForm(targetClass)
                            );

                            List<OWLAxiom> axioms = Arrays.asList(membershipAxiom, equivAxiom);

                            ExplanationPath path = new ExplanationPath(
                                    axioms,
                                    "Equivalent class: " + getShortForm(equivClass) + " â‰¡ " + getShortForm(targetClass),
                                    ExplanationType.EQUIVALENT_CLASS,
                                    axioms.size()
                            );
                            path.setInferred(true);
                            path.setJustifications(justifications);
                            allPaths.add(path);

                            LOGGER.debug("Added equivalent class path: {}", equivClass);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in equivalent class reasoning: {}", e.getMessage());
        }
    }

    private void addPropertyBasedClassPaths(OWLNamedIndividual individual, OWLClass targetClass, Set<ExplanationPath> allPaths) {
        try {
            // Check if target class is defined through restrictions
            for (OWLEquivalentClassesAxiom equivAxiom : ontology.getEquivalentClassesAxioms(targetClass)) {
                for (OWLClassExpression expr : equivAxiom.getClassExpressions()) {
                    if (expr instanceof OWLObjectSomeValuesFrom) {
                        addExistentialRestrictionPath(individual, (OWLObjectSomeValuesFrom) expr, equivAxiom, allPaths);
                    } else if (expr instanceof OWLObjectAllValuesFrom) {
                        addUniversalRestrictionPath(individual, (OWLObjectAllValuesFrom) expr, equivAxiom, allPaths);
                    }
                }
            }

            // Check subclass axioms for restrictions
            for (OWLSubClassOfAxiom subAxiom : ontology.getSubClassAxiomsForSuperClass(targetClass)) {
                OWLClassExpression subExpr = subAxiom.getSubClass();
                if (subExpr instanceof OWLObjectSomeValuesFrom) {
                    addExistentialRestrictionPath(individual, (OWLObjectSomeValuesFrom) subExpr, subAxiom, allPaths);
                } else if (subExpr instanceof OWLObjectAllValuesFrom) {
                    addUniversalRestrictionPath(individual, (OWLObjectAllValuesFrom) subExpr, subAxiom, allPaths);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in property-based reasoning: {}", e.getMessage());
        }
    }

    private void addExistentialRestrictionPath(OWLNamedIndividual individual, OWLObjectSomeValuesFrom restriction,
                                               OWLAxiom containingAxiom, Set<ExplanationPath> allPaths) {
        try {
            if (restriction.getProperty().isAnonymous()) return;

            OWLObjectProperty property = restriction.getProperty().asOWLObjectProperty();
            OWLClassExpression filler = restriction.getFiller();

            Set<OWLNamedIndividual> propertyValues = reasoner.getObjectPropertyValues(individual, property).getFlattened();

            for (OWLNamedIndividual value : propertyValues) {
                if (!filler.isAnonymous()) {
                    OWLClass fillerClass = filler.asOWLClass();
                    if (reasoner.getTypes(value, false).getFlattened().contains(fillerClass)) {
                        List<String> justifications = Arrays.asList(
                                getShortForm(individual) + " " + getShortForm(property) + " " + getShortForm(value),
                                getShortForm(value) + " rdf:type " + getShortForm(fillerClass),
                                "âˆƒ" + getShortForm(property) + "." + getShortForm(fillerClass) + " restriction satisfied"
                        );

                        List<OWLAxiom> axioms = Arrays.asList(
                                dataFactory.getOWLObjectPropertyAssertionAxiom(property, individual, value),
                                dataFactory.getOWLClassAssertionAxiom(fillerClass, value),
                                containingAxiom
                        );

                        ExplanationPath path = new ExplanationPath(
                                axioms,
                                String.format("Existential restriction: âˆƒ%s.%s satisfied by %s",
                                        getShortForm(property), getShortForm(fillerClass), getShortForm(value)),
                                ExplanationType.EXISTENTIAL,
                                axioms.size()
                        );
                        path.setInferred(true);
                        path.setJustifications(justifications);
                        allPaths.add(path);

                        LOGGER.debug("Added existential restriction path");
                        break; // One example is sufficient
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in existential restriction: {}", e.getMessage());
        }
    }

    private void addUniversalRestrictionPath(OWLNamedIndividual individual, OWLObjectAllValuesFrom restriction,
                                             OWLAxiom containingAxiom, Set<ExplanationPath> allPaths) {
        try {
            if (restriction.getProperty().isAnonymous()) return;

            OWLObjectProperty property = restriction.getProperty().asOWLObjectProperty();
            OWLClassExpression filler = restriction.getFiller();

            Set<OWLNamedIndividual> propertyValues = reasoner.getObjectPropertyValues(individual, property).getFlattened();

            if (propertyValues.isEmpty()) {
                // Vacuous satisfaction
                List<String> justifications = Arrays.asList(
                        "No " + getShortForm(property) + " values for " + getShortForm(individual),
                        "âˆ€" + getShortForm(property) + "." + getShortForm(filler) + " vacuously satisfied"
                );

                ExplanationPath path = new ExplanationPath(
                        Arrays.asList(containingAxiom),
                        String.format("Universal restriction: âˆ€%s.%s vacuously satisfied",
                                getShortForm(property), getShortForm(filler)),
                        ExplanationType.UNIVERSAL,
                        1
                );
                path.setInferred(true);
                path.setJustifications(justifications);
                allPaths.add(path);

                LOGGER.debug("Added vacuous universal restriction path");
            }
        } catch (Exception e) {
            LOGGER.debug("Error in universal restriction: {}", e.getMessage());
        }
    }

    private void addDomainRangeClassPaths(OWLNamedIndividual individual, OWLClass targetClass, Set<ExplanationPath> allPaths) {
        try {
            // Check if class membership comes from property domain
            for (OWLObjectPropertyDomainAxiom domainAxiom : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_DOMAIN)) {
                if (domainAxiom.getDomain().equals(targetClass) && !domainAxiom.getProperty().isAnonymous()) {
                    OWLObjectProperty property = domainAxiom.getProperty().asOWLObjectProperty();

                    Set<OWLNamedIndividual> propertyValues = reasoner.getObjectPropertyValues(individual, property).getFlattened();
                    if (!propertyValues.isEmpty()) {
                        OWLNamedIndividual someValue = propertyValues.iterator().next();

                        List<String> justifications = Arrays.asList(
                                getShortForm(individual) + " " + getShortForm(property) + " " + getShortForm(someValue),
                                "domain(" + getShortForm(property) + ") = " + getShortForm(targetClass)
                        );

                        List<OWLAxiom> axioms = Arrays.asList(
                                domainAxiom,
                                dataFactory.getOWLObjectPropertyAssertionAxiom(property, individual, someValue)
                        );

                        ExplanationPath path = new ExplanationPath(
                                axioms,
                                String.format("Domain restriction: domain(%s) = %s",
                                        getShortForm(property), getShortForm(targetClass)),
                                ExplanationType.DOMAIN_RANGE,
                                axioms.size()
                        );
                        path.setInferred(true);
                        path.setJustifications(justifications);
                        allPaths.add(path);

                        LOGGER.debug("Added domain restriction path");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in domain/range reasoning: {}", e.getMessage());
        }
    }

    private void addComplexConstructorPaths(OWLNamedIndividual individual, OWLClass targetClass, Set<ExplanationPath> allPaths) {
        try {
            // Check for intersection and union classes
            for (OWLEquivalentClassesAxiom equivAxiom : ontology.getEquivalentClassesAxioms(targetClass)) {
                for (OWLClassExpression expr : equivAxiom.getClassExpressions()) {
                    if (expr instanceof OWLObjectIntersectionOf) {
                        addIntersectionPath(individual, (OWLObjectIntersectionOf) expr, equivAxiom, allPaths);
                    } else if (expr instanceof OWLObjectUnionOf) {
                        addUnionPath(individual, (OWLObjectUnionOf) expr, equivAxiom, allPaths);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in complex constructor reasoning: {}", e.getMessage());
        }
    }

    private void addIntersectionPath(OWLNamedIndividual individual, OWLObjectIntersectionOf intersection,
                                     OWLAxiom containingAxiom, Set<ExplanationPath> allPaths) {
        try {
            List<OWLAxiom> axioms = new ArrayList<>();
            List<String> justifications = new ArrayList<>();
            axioms.add(containingAxiom);

            boolean memberOfAll = true;
            for (OWLClassExpression component : intersection.getOperands()) {
                if (!component.isAnonymous()) {
                    OWLClass componentClass = component.asOWLClass();
                    if (reasoner.getTypes(individual, false).getFlattened().contains(componentClass)) {
                        axioms.add(dataFactory.getOWLClassAssertionAxiom(componentClass, individual));
                        justifications.add(getShortForm(individual) + " rdf:type " + getShortForm(componentClass));
                    } else {
                        memberOfAll = false;
                        break;
                    }
                }
            }

            if (memberOfAll && justifications.size() > 1) {
                ExplanationPath path = new ExplanationPath(
                        axioms,
                        "Intersection class: member of all components",
                        ExplanationType.INTERSECTION,
                        axioms.size()
                );
                path.setInferred(true);
                path.setJustifications(justifications);
                allPaths.add(path);

                LOGGER.debug("Added intersection path");
            }
        } catch (Exception e) {
            LOGGER.debug("Error in intersection reasoning: {}", e.getMessage());
        }
    }

    private void addUnionPath(OWLNamedIndividual individual, OWLObjectUnionOf union,
                              OWLAxiom containingAxiom, Set<ExplanationPath> allPaths) {
        try {
            for (OWLClassExpression component : union.getOperands()) {
                if (!component.isAnonymous()) {
                    OWLClass componentClass = component.asOWLClass();
                    if (reasoner.getTypes(individual, false).getFlattened().contains(componentClass)) {
                        List<String> justifications = Arrays.asList(
                                getShortForm(individual) + " rdf:type " + getShortForm(componentClass),
                                "Union class membership via " + getShortForm(componentClass)
                        );

                        List<OWLAxiom> axioms = Arrays.asList(
                                containingAxiom,
                                dataFactory.getOWLClassAssertionAxiom(componentClass, individual)
                        );

                        ExplanationPath path = new ExplanationPath(
                                axioms,
                                String.format("Union class: member of component '%s'", getShortForm(componentClass)),
                                ExplanationType.UNION,
                                axioms.size()
                        );
                        path.setInferred(true);
                        path.setJustifications(justifications);
                        allPaths.add(path);

                        LOGGER.debug("Added union path");
                        break; // One component is sufficient for union
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in union reasoning: {}", e.getMessage());
        }
    }

    // ==================== PROPERTY ASSERTION EXPLANATION METHODS ====================

    private void addDirectPropertyAssertionPaths(OWLNamedIndividual subject, OWLObjectProperty property,
                                                 OWLNamedIndividual object, Set<ExplanationPath> allPaths) {
        try {
            OWLObjectPropertyAssertionAxiom directAssertion = dataFactory.getOWLObjectPropertyAssertionAxiom(property, subject, object);
            if (ontology.containsAxiom(directAssertion)) {
                List<String> justifications = Arrays.asList(
                        getShortForm(subject) + " " + getShortForm(property) + " " + getShortForm(object)
                );

                ExplanationPath directPath = new ExplanationPath(
                        Arrays.asList(directAssertion),
                        "Direct property assertion",
                        ExplanationType.DIRECT_ASSERTION,
                        1
                );
                directPath.setInferred(false);
                directPath.setJustifications(justifications);
                allPaths.add(directPath);

                LOGGER.debug("Added direct property assertion path");
            }
        } catch (Exception e) {
            LOGGER.debug("Error checking direct property assertion: {}", e.getMessage());
        }
    }

    private void addSubPropertyReasoningPaths(OWLNamedIndividual subject, OWLObjectProperty property,
                                              OWLNamedIndividual object, Set<ExplanationPath> allPaths) {
        try {
            for (OWLSubObjectPropertyOfAxiom subPropAxiom : ontology.getObjectSubPropertyAxiomsForSuperProperty(property)) {
                OWLObjectPropertyExpression subProp = subPropAxiom.getSubProperty();
                if (!subProp.isAnonymous()) {
                    OWLObjectProperty subProperty = subProp.asOWLObjectProperty();
                    if (reasoner.getObjectPropertyValues(subject, subProperty).getFlattened().contains(object)) {
                        List<String> justifications = Arrays.asList(
                                getShortForm(subject) + " " + getShortForm(subProperty) + " " + getShortForm(object),
                                getShortForm(subProperty) + " rdfs:subPropertyOf " + getShortForm(property)
                        );

                        List<OWLAxiom> axioms = Arrays.asList(
                                dataFactory.getOWLObjectPropertyAssertionAxiom(subProperty, subject, object),
                                subPropAxiom
                        );

                        ExplanationPath path = new ExplanationPath(
                                axioms,
                                "Sub-property reasoning: " + getShortForm(subProperty) + " âŠ‘ " + getShortForm(property),
                                ExplanationType.SUBPROPERTY,
                                axioms.size()
                        );
                        path.setInferred(true);
                        path.setJustifications(justifications);
                        allPaths.add(path);

                        LOGGER.debug("Added sub-property path: {}", subProperty);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in sub-property reasoning: {}", e.getMessage());
        }
    }

    private void addPropertyCharacteristicPaths(OWLNamedIndividual subject, OWLObjectProperty property,
                                                OWLNamedIndividual object, Set<ExplanationPath> allPaths) {
        try {
            // Symmetric property reasoning
            for (OWLSymmetricObjectPropertyAxiom symAxiom : ontology.getSymmetricObjectPropertyAxioms(property)) {
                if (reasoner.getObjectPropertyValues(object, property).getFlattened().contains(subject)) {
                    List<String> justifications = Arrays.asList(
                            getShortForm(object) + " " + getShortForm(property) + " " + getShortForm(subject),
                            "SymmetricObjectProperty(" + getShortForm(property) + ")"
                    );

                    List<OWLAxiom> axioms = Arrays.asList(
                            dataFactory.getOWLObjectPropertyAssertionAxiom(property, object, subject),
                            symAxiom
                    );

                    ExplanationPath path = new ExplanationPath(
                            axioms,
                            "Symmetric property reasoning: " + getShortForm(property) + " is symmetric",
                            ExplanationType.SYMMETRIC_PROPERTY,
                            axioms.size()
                    );
                    path.setInferred(true);
                    path.setJustifications(justifications);
                    allPaths.add(path);

                    LOGGER.debug("Added symmetric property path");
                }
            }

            // Transitive property reasoning
            for (OWLTransitiveObjectPropertyAxiom transAxiom : ontology.getTransitiveObjectPropertyAxioms(property)) {
                Set<OWLNamedIndividual> intermediates = reasoner.getObjectPropertyValues(subject, property).getFlattened();

                for (OWLNamedIndividual intermediate : intermediates) {
                    if (!intermediate.equals(object) &&
                            reasoner.getObjectPropertyValues(intermediate, property).getFlattened().contains(object)) {

                        List<String> justifications = Arrays.asList(
                                getShortForm(subject) + " " + getShortForm(property) + " " + getShortForm(intermediate),
                                getShortForm(intermediate) + " " + getShortForm(property) + " " + getShortForm(object),
                                "TransitiveObjectProperty(" + getShortForm(property) + ")"
                        );

                        List<OWLAxiom> axioms = Arrays.asList(
                                dataFactory.getOWLObjectPropertyAssertionAxiom(property, subject, intermediate),
                                dataFactory.getOWLObjectPropertyAssertionAxiom(property, intermediate, object),
                                transAxiom
                        );

                        ExplanationPath path = new ExplanationPath(
                                axioms,
                                "Transitive property reasoning via " + getShortForm(intermediate),
                                ExplanationType.TRANSITIVE_PROPERTY,
                                axioms.size()
                        );
                        path.setInferred(true);
                        path.setJustifications(justifications);
                        allPaths.add(path);

                        LOGGER.debug("Added transitive property path via {}", intermediate);
                        break; // One intermediate is sufficient
                    }
                }
            }

            // Inverse property reasoning
            for (OWLInverseObjectPropertiesAxiom invAxiom : ontology.getInverseObjectPropertyAxioms(property)) {
                for (OWLObjectPropertyExpression invProp : invAxiom.getProperties()) {
                    if (!invProp.equals(property) && !invProp.isAnonymous()) {
                        OWLObjectProperty inverseProperty = invProp.asOWLObjectProperty();
                        if (reasoner.getObjectPropertyValues(object, inverseProperty).getFlattened().contains(subject)) {
                            List<String> justifications = Arrays.asList(
                                    getShortForm(object) + " " + getShortForm(inverseProperty) + " " + getShortForm(subject),
                                    getShortForm(property) + " owl:inverseOf " + getShortForm(inverseProperty)
                            );

                            List<OWLAxiom> axioms = Arrays.asList(
                                    dataFactory.getOWLObjectPropertyAssertionAxiom(inverseProperty, object, subject),
                                    invAxiom
                            );

                            ExplanationPath path = new ExplanationPath(
                                    axioms,
                                    "Inverse property reasoning: " + getShortForm(property) + " â‰¡ " + getShortForm(inverseProperty) + "â»",
                                    ExplanationType.INVERSE_PROPERTY,
                                    axioms.size()
                            );
                            path.setInferred(true);
                            path.setJustifications(justifications);
                            allPaths.add(path);

                            LOGGER.debug("Added inverse property path");
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in property characteristics reasoning: {}", e.getMessage());
        }
    }

    private void addPropertyChainReasoningPaths(OWLNamedIndividual subject, OWLObjectProperty property,
                                                OWLNamedIndividual object, Set<ExplanationPath> allPaths) {
        try {
            for (OWLSubPropertyChainOfAxiom chainAxiom : ontology.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF)) {
                if (chainAxiom.getSuperProperty().equals(property)) {
                    List<OWLObjectPropertyExpression> chain = chainAxiom.getPropertyChain();

                    // Find chain path (simplified for 2-property chains)
                    if (chain.size() == 2 && !chain.get(0).isAnonymous() && !chain.get(1).isAnonymous()) {
                        OWLObjectProperty prop1 = chain.get(0).asOWLObjectProperty();
                        OWLObjectProperty prop2 = chain.get(1).asOWLObjectProperty();

                        Set<OWLNamedIndividual> intermediates = reasoner.getObjectPropertyValues(subject, prop1).getFlattened();

                        for (OWLNamedIndividual intermediate : intermediates) {
                            if (reasoner.getObjectPropertyValues(intermediate, prop2).getFlattened().contains(object)) {
                                List<String> justifications = Arrays.asList(
                                        getShortForm(subject) + " " + getShortForm(prop1) + " " + getShortForm(intermediate),
                                        getShortForm(intermediate) + " " + getShortForm(prop2) + " " + getShortForm(object),
                                        "PropertyChain(" + getShortForm(prop1) + " âˆ˜ " + getShortForm(prop2) + ") âŠ‘ " + getShortForm(property)
                                );

                                List<OWLAxiom> axioms = Arrays.asList(
                                        chainAxiom,
                                        dataFactory.getOWLObjectPropertyAssertionAxiom(prop1, subject, intermediate),
                                        dataFactory.getOWLObjectPropertyAssertionAxiom(prop2, intermediate, object)
                                );

                                ExplanationPath path = new ExplanationPath(
                                        axioms,
                                        "Property chain reasoning via " + getShortForm(intermediate),
                                        ExplanationType.PROPERTY_CHAIN,
                                        axioms.size()
                                );
                                path.setInferred(true);
                                path.setJustifications(justifications);
                                allPaths.add(path);

                                LOGGER.debug("Added property chain path via {}", intermediate);
                                break; // One chain is sufficient
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in property chain reasoning: {}", e.getMessage());
        }
    }

    // ==================== LEGACY METHODS (for backward compatibility) ====================


    private String formatPathsAsString(Set<ExplanationPath> paths, String header) {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n");
        sb.append("Found ").append(paths.size()).append(" explanation paths:\n");

        int pathIndex = 1;
        for (ExplanationPath path : paths) {
            sb.append("Path ").append(pathIndex++).append(": ");
            sb.append(path.getDescription()).append("\n");
            if (path.getJustifications() != null) {
                for (String justification : path.getJustifications()) {
                    sb.append("  - ").append(justification).append("\n");
                }
            }
            sb.append("  Tag: ").append(tagger.tagExplanation(path)).append("\n\n");
        }

        return sb.toString();
    }

    // ==================== UTILITY METHODS ====================

    private String getShortForm(Object obj) {
        return OntologyUtils.getShortForm(obj);
    }
}