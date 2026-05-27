package com.example.difficulty;

import com.example.explanation.ExplanationPath;
import com.example.difficulty.ExplicitInferredCalculator.InferenceStatus;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.OptionalInt;

/**
 * Combines all difficulty-related feature calculators.
 *
 * This class does NOT decide the final difficulty label yet.
 * It only extracts the separate feature values:
 *
 * - reasoning_depth
 * - premise_count
 * - abox_size
 * - explicit_or_inferred
 * - justification_complexity_score
 *
 * Later, we can use these values for stratification:
 * easy / medium / hard.
 */
public class QueryDifficultyFeatureExtractor {

    private final ReasoningDepthCalculator reasoningDepthCalculator;
    private final PremiseCountCalculator premiseCountCalculator;
    private final ABoxSizeCalculator aboxSizeCalculator;
    private final ExplicitInferredCalculator explicitInferredCalculator;

    /**
     * Constructor.
     *
     * Each calculator has one job:
     *
     * ReasoningDepthCalculator:
     *   calculates reasoning_depth from the shortest explanation.
     *
     * PremiseCountCalculator:
     *   calculates premise_count from the shortest explanation.
     *
     * ABoxSizeCalculator:
     *   calculates abox_size from the current ontology/subgraph.
     *
     * ExplicitInferredCalculator:
     *   checks whether the query axiom is explicit, inferred, or not entailed.
     *
     * JustificationComplexity:
     *   is not created here because it is a utility class with static methods.
     */
    public QueryDifficultyFeatureExtractor() {
        this.reasoningDepthCalculator = new ReasoningDepthCalculator();
        this.premiseCountCalculator = new PremiseCountCalculator();
        this.aboxSizeCalculator = new ABoxSizeCalculator();
        this.explicitInferredCalculator = new ExplicitInferredCalculator();
    }

    /**
     * @param ontology          The current ontology/subgraph.
     * @param reasoner          The Pellet/OWL reasoner for this ontology.
     * @param queryAxiom        The OWL axiom represented by the query.
     * @param explanationPaths  The explanations generated for this same queryAxiom.
     *
     * @return QueryDifficultyFeatures object containing all feature values.
     */
    public QueryDifficultyFeatures extractFeatures(
            OWLOntology ontology,
            OWLReasoner reasoner,
            OWLAxiom queryAxiom,
            Collection<ExplanationPath> explanationPaths
    ) {

        /*
         * Feature 1: reasoning depth (uses the shortest explanation)
         */
        OptionalInt reasoningDepthOptional =
                reasoningDepthCalculator.calculate(explanationPaths);

        Integer reasoningDepth = reasoningDepthOptional.isPresent()
                ? reasoningDepthOptional.getAsInt()
                : null;

        /*
         * Feature 2: premise count (uses the shortest explanation)
         */
        OptionalInt premiseCountOptional =
                premiseCountCalculator.calculate(explanationPaths);

        Integer premiseCount = premiseCountOptional.isPresent()
                ? premiseCountOptional.getAsInt()
                : null;

        /*
         * Feature 3: ABox size
         */
        int aboxSize = aboxSizeCalculator.calculate(ontology);

        /*
         * Feature 4: explicit or inferred
         */
        InferenceStatus inferenceStatus =
                explicitInferredCalculator.calculate(ontology, reasoner, queryAxiom);

        /*
         * Feature 5: justification complexity score (if there is no explanation or no query axiom  score=null)
         */
        Integer justificationComplexityScore = null;

        if (explanationPaths != null && !explanationPaths.isEmpty() && queryAxiom != null) {
            justificationComplexityScore =
                    JustificationComplexity.calculateFromShortestExplanation(
                            new ArrayList<>(explanationPaths),
                            queryAxiom
                    );
        }

        /*
         * Return all features together in one object.
         */
        return new QueryDifficultyFeatures(
                reasoningDepth,
                premiseCount,
                aboxSize,
                inferenceStatus,
                justificationComplexityScore
        );
    }

    /**
     * Small data class that stores the extracted feature values.
     *
     * We use Integer instead of int for reasoningDepth, premiseCount,
     * and justificationComplexityScore because these values can be null
     * when no explanation was found.
     */
    public static class QueryDifficultyFeatures {

        private final Integer reasoningDepth;
        private final Integer premiseCount;
        private final int aboxSize;
        private final InferenceStatus inferenceStatus;
        private final Integer justificationComplexityScore;

        public QueryDifficultyFeatures(
                Integer reasoningDepth,
                Integer premiseCount,
                int aboxSize,
                InferenceStatus inferenceStatus,
                Integer justificationComplexityScore
        ) {
            this.reasoningDepth = reasoningDepth;
            this.premiseCount = premiseCount;
            this.aboxSize = aboxSize;
            this.inferenceStatus = inferenceStatus;
            this.justificationComplexityScore = justificationComplexityScore;
        }

        public Integer getReasoningDepth() {
            return reasoningDepth;
        }

        public Integer getPremiseCount() {
            return premiseCount;
        }

        public int getAboxSize() {
            return aboxSize;
        }

        public InferenceStatus getInferenceStatus() {
            return inferenceStatus;
        }

        public Integer getJustificationComplexityScore() {
            return justificationComplexityScore;
        }

        /**
         * Useful when printing/debugging.
         */
        @Override
        public String toString() {
            return "QueryDifficultyFeatures{" +
                    "reasoningDepth=" + reasoningDepth +
                    ", premiseCount=" + premiseCount +
                    ", aboxSize=" + aboxSize +
                    ", inferenceStatus=" + inferenceStatus +
                    ", justificationComplexityScore=" + justificationComplexityScore +
                    '}';
        }
    }
}