package com.example.difficulty;

import com.example.explanation.ExplanationPath;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Computes reasoning depth using the shortest available explanation.
 * 
 * reasoningDepth = numberOfAxiomsInShortestExplanation - 1
 */
public class ReasoningDepthCalculator {

    public OptionalInt calculate(Collection<ExplanationPath> explanationPaths) {
        Optional<ExplanationPath> shortest = findShortestExplanation(explanationPaths);

        if (shortest.isEmpty()) {
            return OptionalInt.empty();
        }

        return OptionalInt.of(calculateForSingleExplanation(shortest.get()));
    }

    /**
     * Finds the shortest explanation.
     *
     * Shortest = explanation with the fewest axioms.
     */
    public Optional<ExplanationPath> findShortestExplanation(Collection<ExplanationPath> explanationPaths) {
        if (explanationPaths == null || explanationPaths.isEmpty()) {
            return Optional.empty();
        }

        return explanationPaths.stream()
                .filter(path -> path != null && path.getAxioms() != null)
                .min(Comparator.comparingInt(path -> path.getAxioms().size()));
    }

    /**
     * Computes reasoning depth for one already-selected explanation.
     */
    public int calculateForSingleExplanation(ExplanationPath path) {
        if (path == null || path.getAxioms() == null) {
            return 0;
        }

        int axiomCount = path.getAxioms().size();

        return Math.max(0, axiomCount - 1);
    }
}
