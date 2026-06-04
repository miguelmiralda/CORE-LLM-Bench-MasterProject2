// com/example/ontology/DefaultOntologyService.java
package com.example.ontology;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ontology.OntologyStats;
import com.example.util.OntologyUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Set;

/**
 * FIXED: Enhanced ontology service that creates fresh managers to avoid duplicate IRI issues
 */
@Service
public class DefaultOntologyService implements OntologyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultOntologyService.class);
    private static final String[] SUPPORTED_EXTENSIONS = {".owl", ".rdf", ".ttl", ".n3"};

    // REMOVED: Don't keep a single manager - create fresh ones
    // private OWLOntologyManager manager;

    public DefaultOntologyService() {
        // No longer need to initialize a single manager
        LOGGER.info("DefaultOntologyService initialized for sequential processing");
    }

    @Override
    public List<OWLOntology> loadOntologiesFromDirectory(String directoryPath) {
        List<OWLOntology> ontologies = new ArrayList<>();

        try {
            LOGGER.info("Loading ontologies from directory: {}", directoryPath);

            File directory = new File(directoryPath);
            if (!directory.exists() || !directory.isDirectory()) {
                throw new RuntimeException("Directory does not exist or is not a directory: " + directoryPath);
            }

            // Get all ontology files
            File[] ontologyFiles = directory.listFiles(this::isOntologyFile);
            if (ontologyFiles == null || ontologyFiles.length == 0) {
                LOGGER.warn("No ontology files found in directory: {}", directoryPath);
                return ontologies;
            }

            LOGGER.info("Found {} ontology files", ontologyFiles.length);

            // Load each ontology file with a FRESH manager to avoid duplicate IRI issues
            for (File file : ontologyFiles) {
                try {
                    OWLOntology ontology = loadOntology(file);
                    if (ontology != null) {
                        ontologies.add(ontology);

                        if (ontologies.size() % 50 == 0) {
                            LOGGER.info("Loaded {} ontologies so far...", ontologies.size());
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load ontology from file {}: {}", file.getName(), e.getMessage());
                }
            }

            LOGGER.info("Successfully loaded {} ontologies from directory", ontologies.size());

        } catch (Exception e) {
            LOGGER.error("Error loading ontologies from directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to load ontologies from directory: " + e.getMessage(), e);
        }

        return ontologies;
    }

    @Override
    public OWLOntology loadOntology(File ontologyFile) {
        // CRITICAL FIX: Create a FRESH manager for each ontology file
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        try {
            LOGGER.debug("Loading ontology from file: {}", ontologyFile.getAbsolutePath());

            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(ontologyFile);

            LOGGER.debug("Successfully loaded ontology: {} with {} axioms",
                    ontology.getOntologyID().getOntologyIRI().orElse(null),
                    ontology.getAxiomCount());

            return ontology;

        } catch (OWLOntologyAlreadyExistsException e) {
            // This should no longer happen with fresh managers, but just in case:
            LOGGER.warn("Ontology already exists (using fresh manager): {}", ontologyFile.getName());
            try {
                // Get the existing ontology and return it
                OWLOntologyID existingID = e.getOntologyID();
                return manager.getOntology(existingID);
            } catch (Exception ex) {
                LOGGER.error("Could not retrieve existing ontology: {}", ex.getMessage());
                throw new RuntimeException("Failed to load ontology: " + ex.getMessage(), ex);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load ontology from file: {}", ontologyFile.getAbsolutePath(), e);
            throw new RuntimeException("Failed to load ontology: " + e.getMessage(), e);
        }
    }

    /**
     * Extract root entity (primary class) for an individual
     */
    public String extractRootEntity(OWLNamedIndividual individual, OWLOntology ontology) {
        // First try to find direct class assertions
        for (OWLClassAssertionAxiom axiom : ontology.getClassAssertionAxioms(individual)) {
            OWLClassExpression classExpression = axiom.getClassExpression();
            if (!classExpression.isAnonymous()) {
                OWLClass clazz = classExpression.asOWLClass();
                if (!clazz.isOWLThing()) {
                    return OntologyUtils.getShortForm(clazz);
                }
            }
        }

        // Fallback: look for any type assertion in the ontology
        try {
            Set<OWLClass> types = ontology.getClassesInSignature();
            for (OWLClass clazz : types) {
                if (!clazz.isOWLThing()) {
                    // Check if this individual is asserted to be of this type
                    OWLClassAssertionAxiom assertion =
                            ontology.getOWLOntologyManager().getOWLDataFactory()
                                    .getOWLClassAssertionAxiom(clazz, individual);
                    if (ontology.containsAxiom(assertion)) {
                        return OntologyUtils.getShortForm(clazz);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore and use fallback
        }

        return "Thing"; // Final fallback
    }

    @Override
    public OntologyStats getStats(OWLOntology ontology) {
        int classCount = ontology.getClassesInSignature().size();
        int individualCount = ontology.getIndividualsInSignature().size();
        int objectPropertyCount = ontology.getObjectPropertiesInSignature().size();
        int dataPropertyCount = ontology.getDataPropertiesInSignature().size();

        return new OntologyStats(classCount, individualCount, objectPropertyCount, dataPropertyCount);
    }

    @Override
    public OntologyStats getCombinedStats(List<OWLOntology> ontologies) {
        int totalClasses = 0;
        int totalIndividuals = 0;
        int totalObjectProperties = 0;
        int totalDataProperties = 0;

        for (OWLOntology ontology : ontologies) {
            OntologyStats stats = getStats(ontology);
            totalClasses += stats.getClassCount();
            totalIndividuals += stats.getIndividualCount();
            totalObjectProperties += stats.getObjectPropertyCount();
            totalDataProperties += stats.getDataPropertyCount();
        }

        return new OntologyStats(totalClasses, totalIndividuals, totalObjectProperties, totalDataProperties);
    }

    private boolean isOntologyFile(File file) {
        if (file.isDirectory()) return false;

        String fileName = file.getName().toLowerCase();
        return Arrays.stream(SUPPORTED_EXTENSIONS)
                .anyMatch(fileName::endsWith);
    }

    @Override
    public void close() throws Exception {
        // No longer need to clean up a single manager
        LOGGER.info("Ontology service closed successfully");
    }
}