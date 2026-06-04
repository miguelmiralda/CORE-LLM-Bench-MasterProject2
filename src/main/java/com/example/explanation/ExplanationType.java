// com/example/explanation/ExplanationType.java
package com.example.explanation;

/**
 * Types of explanations for ontological reasoning
 */
public enum ExplanationType {
    DIRECT_ASSERTION("Direct Assertion"),
    SUBSUMPTION("Subsumption"),
    SUBPROPERTY("Subproperty"),
    TRANSITIVE_PROPERTY("Transitive Property"),
    SYMMETRIC_PROPERTY("Symmetric Property"),
    INVERSE_PROPERTY("Inverse Property"),
    FUNCTIONAL_PROPERTY("Functional Property"),
    PROPERTY_CHAIN("Property Chain"),
    EQUIVALENT_CLASS("Equivalent Class"),
    DISJOINT_CLASS("Disjoint Class"),
    DOMAIN_RANGE("Domain/Range"),
    CARDINALITY("Cardinality"),
    EXISTENTIAL("Existential"),
    UNIVERSAL("Universal"),
    INTERSECTION("Intersection"),
    UNION("Union"),
    COMPLEMENT("Complement"),
    REASONING("Generic Reasoning"),
    INFERENCE("Inference"),
    TRANSITIVITY("Transitivity"),
    SYMMETRY("Symmetry"),
    INVERSE("Inverse"),
    UNKNOWN("Unknown");

    private final String displayName;

    ExplanationType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}