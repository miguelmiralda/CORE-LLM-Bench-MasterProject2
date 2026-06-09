package com.example.difficulty;

import com.example.explanation.ExplanationPath;
import org.semanticweb.owlapi.model.*;

import java.util.*;

/**
 * Calculates a partial Horridge-style justification complexity score.
 *
 * Implemented components:
 *
 * C1 = AxiomTypes
 * C7 = ModalDepth
 * C8 = SignatureDifference
 * C9 = AxiomTypeDiff
 *
 * Final score:
 *
 * score =
 *      C1 * 100
 *    + C7 * 50
 *    + C8 * 50
 *    + C9 * 50
 *
 * This class only returns the final score.
 */
public class JustificationComplexity {

    private static final int WEIGHT_C1_AXIOM_TYPES = 100;
    private static final int WEIGHT_C7_MODAL_DEPTH = 50;
    private static final int WEIGHT_C8_SIGNATURE_DIFFERENCE = 50;
    private static final int WEIGHT_C9_AXIOM_TYPE_DIFF = 50;

    private JustificationComplexity() {
        // Utility class. Do not instantiate.
    }

    /**
     * It takes all explanation paths,
     * selects the shortest explanation,
     * treats that shortest explanation as J,
     * calculates the final Horridge-style score for queryAxiom eta.
     */
    public static int calculateFromShortestExplanation(List<ExplanationPath> explanationPaths,
                                                       OWLAxiom queryAxiom) {

        if (queryAxiom == null) {
            throw new IllegalArgumentException("queryAxiom cannot be null.");
        }

        if (explanationPaths == null || explanationPaths.isEmpty()) {
            throw new IllegalArgumentException("explanationPaths cannot be null or empty.");
        }

        ExplanationPath shortestExplanation = selectShortestExplanation(explanationPaths);

        List<OWLAxiom> justificationAxioms = extractAxioms(shortestExplanation);

        return calculateFinalScore(justificationAxioms, queryAxiom);
    }

    /**
     * Alternative method if you already have the justification axioms J.
     */
    public static int calculateFinalScore(Collection<? extends OWLAxiom> justificationAxioms,
                                          OWLAxiom queryAxiom) {

        if (queryAxiom == null) {
            throw new IllegalArgumentException("queryAxiom cannot be null.");
        }

        Collection<? extends OWLAxiom> J =
                justificationAxioms == null ? Collections.emptyList() : justificationAxioms;

        int c1 = calculateC1AxiomTypes(J, queryAxiom);
        int c7 = calculateC7ModalDepth(J);
        int c8 = calculateC8SignatureDifference(J, queryAxiom);
        int c9 = calculateC9AxiomTypeDiff(J, queryAxiom);

        return (c1 * WEIGHT_C1_AXIOM_TYPES)
                + (c7 * WEIGHT_C7_MODAL_DEPTH)
                + (c8 * WEIGHT_C8_SIGNATURE_DIFFERENCE)
                + (c9 * WEIGHT_C9_AXIOM_TYPE_DIFF);
    }

    /**
     * Selects the shortest explanation.
     *
     * First criterion:
     * - explanation with fewer axioms
     *
     * Tie-breaker:
     * - explanation with lower stored complexity
     */
    private static ExplanationPath selectShortestExplanation(List<ExplanationPath> explanationPaths) {
        return explanationPaths.stream()
                .filter(Objects::nonNull)
                .min(
                        Comparator
                                .comparingInt((ExplanationPath path) ->
                                        path.getAxioms() == null
                                                ? Integer.MAX_VALUE
                                                : path.getAxioms().size()
                                )
                                .thenComparingInt(ExplanationPath::getComplexity)
                )
                .orElseThrow(() -> new IllegalArgumentException("No valid explanation path found."));
    }

    /**
     * Extracts OWLAxiom objects from the selected ExplanationPath.
     *
     * Written defensively because some versions of ExplanationPath may use raw lists.
     */
    private static List<OWLAxiom> extractAxioms(ExplanationPath explanationPath) {
        List<OWLAxiom> axioms = new ArrayList<>();

        if (explanationPath == null || explanationPath.getAxioms() == null) {
            return axioms;
        }

        for (Object object : explanationPath.getAxioms()) {
            if (object instanceof OWLAxiom) {
                axioms.add((OWLAxiom) object);
            }
        }

        return axioms;
    }

    /**
     * C1: AxiomTypes
     *
     * Counts how many different OWL axiom types appear in J union {eta}.
     */
    private static int calculateC1AxiomTypes(Collection<? extends OWLAxiom> J,
                                             OWLAxiom eta) {

        Set<AxiomType<?>> axiomTypes = new HashSet<>();

        for (OWLAxiom axiom : J) {
            if (axiom != null) {
                axiomTypes.add(axiom.getAxiomType());
            }
        }

        axiomTypes.add(eta.getAxiomType());

        return axiomTypes.size();
    }

    /**
     * C7: ModalDepth
     *
     * Finds the maximum nesting depth of property restrictions inside J.
     *
     * Example:
     *
     * exists R . A
     * depth = 1
     *
     * exists R . exists S . A
     * depth = 2
     */
    private static int calculateC7ModalDepth(Collection<? extends OWLAxiom> J) {
        int maxDepth = 0;

        for (OWLAxiom axiom : J) {
            if (axiom == null) {
                continue;
            }

            for (OWLClassExpression classExpression : axiom.getNestedClassExpressions()) {
                int depth = modalDepth(classExpression);
                maxDepth = Math.max(maxDepth, depth);
            }
        }

        return maxDepth;
    }

    /**
     * Recursive helper for modal depth.
     */
    private static int modalDepth(OWLClassExpression classExpression) {
        if (classExpression == null) {
            return 0;
        }

        if (!classExpression.isAnonymous()) {
            return 0;
        }

        // Object property restrictions

        if (classExpression instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom expression = (OWLObjectSomeValuesFrom) classExpression;
            return 1 + modalDepth(expression.getFiller());
        }

        if (classExpression instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom expression = (OWLObjectAllValuesFrom) classExpression;
            return 1 + modalDepth(expression.getFiller());
        }

        if (classExpression instanceof OWLObjectMinCardinality) {
            OWLObjectMinCardinality expression = (OWLObjectMinCardinality) classExpression;
            return 1 + modalDepth(expression.getFiller());
        }

        if (classExpression instanceof OWLObjectMaxCardinality) {
            OWLObjectMaxCardinality expression = (OWLObjectMaxCardinality) classExpression;
            return 1 + modalDepth(expression.getFiller());
        }

        if (classExpression instanceof OWLObjectExactCardinality) {
            OWLObjectExactCardinality expression = (OWLObjectExactCardinality) classExpression;
            return 1 + modalDepth(expression.getFiller());
        }

        if (classExpression instanceof OWLObjectHasValue) {
            return 1;
        }

        if (classExpression instanceof OWLObjectHasSelf) {
            return 1;
        }

        // Data property restrictions

        if (classExpression instanceof OWLDataSomeValuesFrom) {
            return 1;
        }

        if (classExpression instanceof OWLDataAllValuesFrom) {
            return 1;
        }

        if (classExpression instanceof OWLDataMinCardinality) {
            return 1;
        }

        if (classExpression instanceof OWLDataMaxCardinality) {
            return 1;
        }

        if (classExpression instanceof OWLDataExactCardinality) {
            return 1;
        }

        if (classExpression instanceof OWLDataHasValue) {
            return 1;
        }

        // Boolean constructors do not increase modal depth.

        if (classExpression instanceof OWLObjectIntersectionOf) {
            OWLObjectIntersectionOf expression = (OWLObjectIntersectionOf) classExpression;
            return maxModalDepth(expression.getOperands());
        }

        if (classExpression instanceof OWLObjectUnionOf) {
            OWLObjectUnionOf expression = (OWLObjectUnionOf) classExpression;
            return maxModalDepth(expression.getOperands());
        }

        if (classExpression instanceof OWLObjectComplementOf) {
            OWLObjectComplementOf expression = (OWLObjectComplementOf) classExpression;
            return modalDepth(expression.getOperand());
        }

        if (classExpression instanceof OWLObjectOneOf) {
            return 0;
        }

        // Fallback for unusual anonymous class expressions.
        int maxDepth = 0;

        for (OWLClassExpression nested : classExpression.getNestedClassExpressions()) {
            if (!nested.equals(classExpression)) {
                maxDepth = Math.max(maxDepth, modalDepth(nested));
            }
        }

        return maxDepth;
    }

    private static int maxModalDepth(Set<OWLClassExpression> operands) {
        int maxDepth = 0;

        for (OWLClassExpression operand : operands) {
            maxDepth = Math.max(maxDepth, modalDepth(operand));
        }

        return maxDepth;
    }

    /**
     * C8: SignatureDifference
     *
     * Counts how many named entities appear in eta but not in J.
     */
    private static int calculateC8SignatureDifference(Collection<? extends OWLAxiom> J,
                                                      OWLAxiom eta) {

        Set<OWLEntity> justificationSignature = new HashSet<>();

        for (OWLAxiom axiom : J) {
            if (axiom != null) {
                justificationSignature.addAll(axiom.getSignature());
            }
        }

        Set<OWLEntity> querySignature = new HashSet<>(eta.getSignature());

        querySignature.removeAll(justificationSignature);

        return querySignature.size();
    }

    /**
     * C9: AxiomTypeDiff
     *
     * Returns 1 if eta's axiom type does not appear in J.
     * Returns 0 otherwise.
     */
    private static int calculateC9AxiomTypeDiff(Collection<? extends OWLAxiom> J,
                                                OWLAxiom eta) {

        AxiomType<?> queryAxiomType = eta.getAxiomType();

        for (OWLAxiom axiom : J) {
            if (axiom != null && axiom.getAxiomType().equals(queryAxiomType)) {
                return 0;
            }
        }

        return 1;
    }

     /**
     * Calculates the separate Horridge-style components for one justification J
     * and one query axiom eta.
     *
     * J   = explanation axioms
     * eta = query axiom / entailment axiom
     */
    public static JustificationComplexityComponents calculateComponents(
            Collection<OWLAxiom> justificationAxioms,
            OWLAxiom queryAxiom
    ) {
        if (queryAxiom == null) {
            throw new IllegalArgumentException("queryAxiom cannot be null.");
        }

        Collection<OWLAxiom> J =
                justificationAxioms == null ? Collections.emptyList() : justificationAxioms;

        int c1 = calculateC1AxiomTypes(J, queryAxiom);
        int c7 = calculateC7ModalDepth(J);
        int c8 = calculateC8SignatureDifference(J, queryAxiom);
        int c9 = calculateC9AxiomTypeDiff(J, queryAxiom);

        int finalScore =
                (c1 * WEIGHT_C1_AXIOM_TYPES)
                        + (c7 * WEIGHT_C7_MODAL_DEPTH)
                        + (c8 * WEIGHT_C8_SIGNATURE_DIFFERENCE)
                        + (c9 * WEIGHT_C9_AXIOM_TYPE_DIFF);

        return new JustificationComplexityComponents(
                c1,
                c7,
                c8,
                c9,
                finalScore
        );
    }

    /**
     * Calculates C1/C7/C8/C9 over all explanation paths for the current triple.
     *
     * This is the version we want for the first integration:
     *
     * BIN:
     *   use paths for that triple.
     *
     * MC:
     *   use the same current behavior as tag length,
     *   meaning paths for the current triple.
     *
     * For multiple paths, this returns the MAX component values.
     */
    public static JustificationComplexityStats calculateMaxStatsFromExplanationPaths(
            Collection<ExplanationPath> explanationPaths,
            OWLAxiom queryAxiom
    ) {
        if (queryAxiom == null) {
            return JustificationComplexityStats.empty();
        }

        if (explanationPaths == null || explanationPaths.isEmpty()) {
            return JustificationComplexityStats.empty();
        }

        int c1Max = Integer.MIN_VALUE;
        int c7Max = Integer.MIN_VALUE;
        int c8Max = Integer.MIN_VALUE;
        int c9Max = Integer.MIN_VALUE;
        int scoreMax = Integer.MIN_VALUE;
        int count = 0;

        for (ExplanationPath path : explanationPaths) {
            if (path == null) {
                continue;
            }

            List<OWLAxiom> justificationAxioms = extractAxioms(path);

            JustificationComplexityComponents components =
                    calculateComponents(justificationAxioms, queryAxiom);

            c1Max = Math.max(c1Max, components.c1AxiomTypes());
            c7Max = Math.max(c7Max, components.c7ModalDepth());
            c8Max = Math.max(c8Max, components.c8SignatureDifference());
            c9Max = Math.max(c9Max, components.c9AxiomTypeDiff());
            scoreMax = Math.max(scoreMax, components.finalScore());

            count++;
        }

        if (count == 0) {
            return JustificationComplexityStats.empty();
        }

        return new JustificationComplexityStats(
                c1Max,
                c7Max,
                c8Max,
                c9Max,
                scoreMax,
                count
        );
    }   
}