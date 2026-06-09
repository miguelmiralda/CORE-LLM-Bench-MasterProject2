package com.example.difficulty;

/**
 * Aggregated C1/C7/C8/C9 values over all explanation paths
 * for the current triple.
 * (use MAX values)
 */
public record JustificationComplexityStats(
        int c1AxiomTypesMax,
        int c7ModalDepthMax,
        int c8SignatureDifferenceMax,
        int c9AxiomTypeDiffMax,
        int finalScoreMax,
        int explanationPathCount
) {
    public static JustificationComplexityStats empty() {
        return new JustificationComplexityStats(
                -1,
                -1,
                -1,
                -1,
                -1,
                0
        );
    }
}
