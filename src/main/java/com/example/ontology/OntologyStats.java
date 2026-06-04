// com/example/ontology/OntologyStats.java
package com.example.ontology;

/**
 * Statistics about an ontology
 */
public class OntologyStats {
    private final int classCount;
    private final int individualCount;
    private final int objectPropertyCount;
    private final int dataPropertyCount;

    public OntologyStats(int classCount, int individualCount, int objectPropertyCount, int dataPropertyCount) {
        this.classCount = classCount;
        this.individualCount = individualCount;
        this.objectPropertyCount = objectPropertyCount;
        this.dataPropertyCount = dataPropertyCount;
    }

    public int getClassCount() { return classCount; }
    public int getIndividualCount() { return individualCount; }
    public int getObjectPropertyCount() { return objectPropertyCount; }
    public int getDataPropertyCount() { return dataPropertyCount; }
    public int getTotalEntityCount() { return classCount + individualCount + objectPropertyCount + dataPropertyCount; }

    @Override
    public String toString() {
        return String.format("OntologyStats{classes=%d, individuals=%d, objectProperties=%d, dataProperties=%d, total=%d}",
                classCount, individualCount, objectPropertyCount, dataPropertyCount, getTotalEntityCount());
    }
}