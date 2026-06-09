package com.example.difficulty;

/**
 * Stores the 4 justification-complexity components
 * and the final weighted score.
 *
 * C1 = AxiomTypes
 * C7 = ModalDepth
 * C8 = SignatureDifference
 * C9 = AxiomTypeDiff
 */
public record JustificationComplexityComponents(
        int c1AxiomTypes,
        int c7ModalDepth,
        int c8SignatureDifference,
        int c9AxiomTypeDiff,
        int finalScore
) {
}
