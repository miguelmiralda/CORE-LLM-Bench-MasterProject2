package com.example.difficulty;

import com.example.explanation.ExplanationPath;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Computes premise count using the shortest available explanation.
 */
public class PremiseCountCalculator {

    public OptionalInt calculate(Collection<ExplanationPath> explanationPaths) {
        Optional<ExplanationPath> shortest = findShortestExplanation(explanationPaths);

        if (shortest.isEmpty()) {
            return OptionalInt.empty();
        }

        return OptionalInt.of(calculateForSingleExplanation(shortest.get()));
    }

    /**
     * Finds the shortest explanation.
     */
    private Optional<ExplanationPath> findShortestExplanation(Collection<ExplanationPath> explanationPaths) {
        if (explanationPaths == null || explanationPaths.isEmpty()) {
            return Optional.empty();
        }

        return explanationPaths.stream()
                .filter(path -> path != null && path.getAxioms() != null)
                .min(Comparator.comparingInt(path -> path.getAxioms().size()));
    }

    /**
     * Computes premise count for one already-selected explanation.
     */
    private int calculateForSingleExplanation(ExplanationPath path) {
        if (path == null || path.getAxioms() == null) {
            return 0;
        }

        return path.getAxioms().size();
    }
}
