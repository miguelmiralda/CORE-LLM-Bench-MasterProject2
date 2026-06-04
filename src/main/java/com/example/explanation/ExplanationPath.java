// Updated ExplanationPath.java - Add missing methods
package com.example.explanation;

import org.semanticweb.owlapi.model.OWLAxiom;
import java.util.ArrayList;
import java.util.List;

public class ExplanationPath {
    private final List<OWLAxiom> axioms;
    private final String description;
    private final ExplanationType type;
    private final int complexity;
    private boolean isInferred;
    private List<OWLAxiom> intermediateSteps;
    private List<String> justifications;

    public ExplanationPath(List<OWLAxiom> axioms, String description, ExplanationType type, int complexity) {
        this.axioms = new ArrayList<>(axioms);
        this.description = description;
        this.type = type;
        this.complexity = complexity;
        this.isInferred = false;
        this.intermediateSteps = new ArrayList<>();
        this.justifications = new ArrayList<>();
    }

    // Existing getters
    public List<OWLAxiom> getAxioms() { return axioms; }
    public String getDescription() { return description; }
    public ExplanationType getType() { return type; }
    public int getComplexity() { return complexity; }
    public List<String> getJustifications() { return justifications; }

    // Setters
    public void setInferred(boolean inferred) { this.isInferred = inferred; }

    public void setJustifications(List<String> justifications) {
        this.justifications = justifications != null ? new ArrayList<>(justifications) : new ArrayList<>();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        ExplanationPath that = (ExplanationPath) obj;
        return complexity == that.complexity &&
                type == that.type &&
                axioms.equals(that.axioms) &&
                description.equals(that.description);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(axioms, description, type, complexity);
    }

    @Override
    public String toString() {
        return "ExplanationPath{" +
                "type=" + type +
                ", complexity=" + complexity +
                ", description='" + description + '\'' +
                ", axioms=" + axioms.size() +
                ", justifications=" + justifications.size() +
                '}';
    }
}