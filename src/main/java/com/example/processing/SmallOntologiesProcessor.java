// com/example/processing/SmallOntologiesProcessor.java
package com.example.processing;

import com.example.config.ProcessingConfiguration;
import com.example.ontology.OntologyService;
import com.example.reasoning.ReasoningService;
import com.example.reasoning.PelletReasoningService;
import com.example.explanation.ComprehensiveExplanationService;
import com.example.explanation.ExplanationPath;
import com.example.explanation.ExplanationFormatter;
import com.example.explanation.EnhancedExplanationTagger;
import com.example.query.QueryGenerationService;
import com.example.output.OutputService;
import com.example.util.OntologyUtils;
import com.example.util.URIUtils;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * IMPROVED: Sequential processor for handling multiple small ontologies
 * Processes one ontology at a time to prevent memory issues
 */
public class SmallOntologiesProcessor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmallOntologiesProcessor.class);

    // Services
    private final OntologyService ontologyService;
    private final ReasoningService reasoningService;
    private final QueryGenerationService queryService;
    private final OutputService outputService;
    private final ProcessingConfiguration config;
    private final EnhancedExplanationTagger tagger;
    private final PerformanceTracker performanceTracker;

    // Counters for tracking across all ontologies
    private final AtomicLong totalOntologiesProcessed = new AtomicLong(0);
    private final AtomicLong totalInferencesProcessed = new AtomicLong(0);
    private final AtomicLong totalQueriesGenerated = new AtomicLong(0);
    private final AtomicLong totalBinaryQueries = new AtomicLong(0);
    private final AtomicLong totalMultiChoiceQueries = new AtomicLong(0);

    // For tracking MC queries across current ontology only
    private Set<String> currentOntologyMCQueries;

    private enum EntailmentLabel {
        ENTAILED,
        NON_ENTAILED
    }

    private static class InferenceCandidate {
        private final String tripleKey;
        private final Set<ExplanationPath> paths;
        private final EntailmentLabel label;
        private final OWLOntology variantOntology;
        private final OWLAxiom removedAxiom;
        private final String removalStrategy;

        private InferenceCandidate(String tripleKey, Set<ExplanationPath> paths, EntailmentLabel label,
                                   OWLOntology variantOntology) {
            this(tripleKey, paths, label, variantOntology, null, null);
        }

        private InferenceCandidate(String tripleKey, Set<ExplanationPath> paths, EntailmentLabel label,
                                   OWLOntology variantOntology, OWLAxiom removedAxiom, String removalStrategy) {
            this.tripleKey = tripleKey;
            this.paths = paths;
            this.label = label;
            this.variantOntology = variantOntology;
            this.removedAxiom = removedAxiom;
            this.removalStrategy = removalStrategy;
        }
    }

    private static class InferenceBuildResult {
        private final List<InferenceCandidate> candidates;

        private InferenceBuildResult(List<InferenceCandidate> candidates) {
            this.candidates = candidates;
        }
    }

    private static class RemovalVariant {
        private final OWLOntology ontology;
        private final OWLAxiom removedAxiom;
        private final String strategy;

        private RemovalVariant(OWLOntology ontology, OWLAxiom removedAxiom, String strategy) {
            this.ontology = ontology;
            this.removedAxiom = removedAxiom;
            this.strategy = strategy;
        }
    }

    private static class RankedRemovalAxiom {
        private final OWLAxiom axiom;
        private final int frequency;
        private final int targetSignatureOverlap;
        private final int axiomTypePriority;
        private final boolean presentInAllPaths;

        private RankedRemovalAxiom(OWLAxiom axiom, int frequency, int targetSignatureOverlap,
                                   int axiomTypePriority, boolean presentInAllPaths) {
            this.axiom = axiom;
            this.frequency = frequency;
            this.targetSignatureOverlap = targetSignatureOverlap;
            this.axiomTypePriority = axiomTypePriority;
            this.presentInAllPaths = presentInAllPaths;
        }
    }

    public SmallOntologiesProcessor(OntologyService ontologyService,
                                    ReasoningService reasoningService,
                                    QueryGenerationService queryService,
                                    OutputService outputService,
                                    ProcessingConfiguration config) {
        this.ontologyService = ontologyService;
        this.reasoningService = reasoningService;
        this.queryService = queryService;
        this.outputService = outputService;
        this.config = config;
        this.tagger = new EnhancedExplanationTagger();
        this.performanceTracker = new PerformanceTracker();

        LOGGER.info("SmallOntologiesProcessor initialized for SEQUENTIAL processing");
    }

    public ProcessingResult processSmallOntologies(String ontologiesDirectory) {
        ProcessingResult result = new ProcessingResult();
        performanceTracker.start("total_processing");

        try {
            LOGGER.info("Starting SEQUENTIAL processing of small ontologies from: {}", ontologiesDirectory);

            // Step 1: Initialize output service
            outputService.initialize();

            // Step 2: Get list of ontology files (don't load them all at once)
            performanceTracker.start("file_discovery");
            List<File> ontologyFiles = discoverOntologyFiles(ontologiesDirectory);
            performanceTracker.end("file_discovery");

            LOGGER.info("Discovered {} ontology files", ontologyFiles.size());
            if (ontologyFiles.isEmpty()) {
                LOGGER.warn("No ontology files found in directory: {}", ontologiesDirectory);
                result.setError("No ontology files found in directory");
                return result;
            }

            // Step 3: Process each ontology file sequentially
            performanceTracker.start("sequential_processing");
            processOntologyFilesSequentially(ontologyFiles, result);
            performanceTracker.end("sequential_processing");

            // Step 4: Finalize results
            finalizeResults(result);

        } catch (Exception e) {
            LOGGER.error("Error during small ontologies processing", e);
            result.setError("Processing failed: " + e.getMessage());
        } finally {
            performanceTracker.end("total_processing");
            performanceTracker.logSummary();
        }

        return result;
    }

    /**
     * NEW: Discover ontology files without loading them
     */
    private List<File> discoverOntologyFiles(String directoryPath) {
        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            throw new RuntimeException("Directory does not exist or is not a directory: " + directoryPath);
        }

        File[] files = directory.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".owl") ||
                        name.toLowerCase().endsWith(".ttl") ||
                        name.toLowerCase().endsWith(".rdf") ||
                        name.toLowerCase().endsWith(".n3"));

        return files != null ? Arrays.asList(files) : new ArrayList<>();
    }

    /**
     * REPLACED: Process ontology files one by one to avoid memory issues
     */
    private void processOntologyFilesSequentially(List<File> ontologyFiles, ProcessingResult result) {
        for (int i = 0; i < ontologyFiles.size(); i++) {
            File ontologyFile = ontologyFiles.get(i);

            try {
                LOGGER.info("Processing file {}/{}: {}",
                        i + 1, ontologyFiles.size(), ontologyFile.getName());

                // Process single ontology file and immediately write outputs
                boolean success = processSingleOntologyFile(ontologyFile, result);

                if (success) {
                    totalOntologiesProcessed.incrementAndGet();
                }

                // Force garbage collection after each ontology
                System.gc();

                // Log progress every 10 files
                if ((i + 1) % 10 == 0) {
                    long processed = totalOntologiesProcessed.get();
                    LOGGER.info("Progress: {}/{} files processed ({:.1f}%)",
                            processed, ontologyFiles.size(), (processed * 100.0) / ontologyFiles.size());
                    logMemoryUsage();
                }

            } catch (Exception e) {
                LOGGER.warn("Error processing ontology file {}: {}", ontologyFile.getName(), e.getMessage());
                result.addError("Failed to process file " + ontologyFile.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * NEW: Process a single ontology file completely and write outputs immediately
     */
    private boolean processSingleOntologyFile(File ontologyFile, ProcessingResult result) {
        OWLOntology ontology = null;
        ComprehensiveExplanationService explanationService = null;

        try {
            // Reset MC query tracking for this ontology
            currentOntologyMCQueries = new HashSet<>();

            // Load single ontology
            ontology = ontologyService.loadOntology(ontologyFile);
            LOGGER.debug("Loaded ontology: {} with {} axioms",
                    ontologyFile.getName(), ontology.getAxiomCount());

            // UPDATED: Extract root entity from TTL filename (not from ontology IRI)
            String rootEntity = extractRootEntityFromFilename(ontologyFile);
            LOGGER.debug("Extracted root entity from filename: {}", rootEntity);

            // Calculate and set TBox and ABox sizes for this ontology
            int calculatedTBoxSize = OntologyUtils.calculateTBoxSize(ontology);
            int calculatedABoxSize = OntologyUtils.calculateABoxSize(ontology);
            calculateOntologyStats(calculatedTBoxSize, calculatedABoxSize);

            // Initialize reasoner for this ontology
            reasoningService.initializeReasoner(ontology);

            if (!reasoningService.isConsistent()) {
                LOGGER.warn("Inconsistent ontology detected: {}", ontologyFile.getName());
                result.addWarning("Inconsistent ontology: " + ontologyFile.getName());
                return false;
            }

            // Create explanation service for this ontology
            explanationService = new ComprehensiveExplanationService(
                    reasoningService.getReasoner(), ontology);

            // Extract inferences and process them immediately
            Map<String, Set<ExplanationPath>> ontologyInferences =
                    extractInferencesWithExplanations(ontology, explanationService);

                // Generate entailed baselines and verified non-entailed variants.
                InferenceBuildResult inferenceBuildResult =
                    buildInferenceCandidates(ontology, ontologyInferences, result);
                String entailedOntologyPath = storeEntailedOntology(ontology, ontologyFile, rootEntity, result);
                Map<InferenceCandidate, String> nonEntailedOntologyPaths =
                        storeNonEntailedOntologyVariants(inferenceBuildResult.candidates, rootEntity, result);

            // Process and write inferences using the instance fields
                processAndWriteInferences(
                        inferenceBuildResult.candidates,
                        ontology,
                        tboxSize,
                        aboxSize,
                        rootEntity,
                        entailedOntologyPath,
                        nonEntailedOntologyPaths,
                        result);
                storeRemovedAxioms(inferenceBuildResult.candidates, nonEntailedOntologyPaths, rootEntity, result);

                totalInferencesProcessed.addAndGet(inferenceBuildResult.candidates.size());
            return true;

        } catch (Exception e) {
            LOGGER.error("Error processing ontology file: {}", ontologyFile.getName(), e);
            return false;
        } finally {
            // CRITICAL: Clean up resources immediately
            cleanupResources(explanationService);
        }
    }

    private int tboxSize = 0;
    private int aboxSize = 0;

    /**
     * Set the TBox and ABox sizes for the current ontology
     */
    public synchronized void calculateOntologyStats(int tboxSize, int aboxSize) {
        this.tboxSize = tboxSize;
        this.aboxSize = aboxSize;
        LOGGER.info("Set ontology sizes - TBox: {}, ABox: {}", tboxSize, aboxSize);
    }

    /**
     * UPDATED: Extract inferences - get INFERRED triples for queries, but explain ASSERTED triples
     */
    private Map<String, Set<ExplanationPath>> extractInferencesWithExplanations(
            OWLOntology ontology, ComprehensiveExplanationService explanationService) {

        Map<String, Set<ExplanationPath>> inferences = new HashMap<>();
        Set<OWLNamedIndividual> individuals = ontology.getIndividualsInSignature();

        LOGGER.debug("Processing {} individuals from ontology", individuals.size());

        for (OWLNamedIndividual individual : individuals) {
            try {
                // Extract class assertions - use INFERRED for queries, but explain how ASSERTED ones could be inferred
                extractClassAssertionInferences(individual, ontology, explanationService, inferences);

                // Extract property assertions - use INFERRED for queries, but explain how ASSERTED ones could be inferred
                extractPropertyAssertionInferences(individual, ontology, explanationService, inferences);

            } catch (Exception e) {
                LOGGER.debug("Error processing individual {}: {}", individual, e.getMessage());
            }
        }

        LOGGER.debug("Extracted {} inferences from ontology", inferences.size());
        return inferences;
    }

    /**
     * UPDATED: Use INFERRED types for query generation, but explain ASSERTED types
     */
    private void extractClassAssertionInferences(OWLNamedIndividual individual,
                                                 OWLOntology ontology,
                                                 ComprehensiveExplanationService explanationService,
                                                 Map<String, Set<ExplanationPath>> inferences) {
        try {
            // Get INFERRED types from reasoner (for query generation)
            Set<OWLClass> inferredTypes = reasoningService.getReasoner()
                    .getTypes(individual, false).getFlattened();

            // Get ASSERTED types from ontology (for explanations)
            Set<OWLClass> assertedTypes = ontology.getClassAssertionAxioms(individual).stream()
                    .map(ax -> ax.getClassExpression())
                    .filter(expr -> !expr.isAnonymous())
                    .map(expr -> expr.asOWLClass())
                    .collect(Collectors.toSet());

            // Process INFERRED types for queries, but generate explanations for ASSERTED types
            for (OWLClass inferredClass : inferredTypes) {
                if (OntologyUtils.isOwlThing(inferredClass)) continue;

                String tripleKey = OntologyUtils.createTripleKey(
                        OntologyUtils.getShortForm(individual),
                        "rdf:type",
                        OntologyUtils.getShortForm(inferredClass)
                );

                // Generate explanations: "How could this inferred class membership be derived?"
                Set<ExplanationPath> paths = explanationService.findExplanationPathsLikeProtege(individual, inferredClass);

                if (!paths.isEmpty()) {
                    inferences.put(tripleKey, paths);

                    LOGGER.debug("Found {} explanation paths for INFERRED type: {} rdf:type {}",
                            paths.size(), OntologyUtils.getShortForm(individual), OntologyUtils.getShortForm(inferredClass));
                }
            }

        } catch (Exception e) {
            LOGGER.debug("Error extracting class assertions for {}: {}", individual, e.getMessage());
        }
    }

    /**
     * UPDATED: Use INFERRED properties for query generation, but explain ASSERTED properties
     */
    private void extractPropertyAssertionInferences(OWLNamedIndividual individual,
                                                    OWLOntology ontology,
                                                    ComprehensiveExplanationService explanationService,
                                                    Map<String, Set<ExplanationPath>> inferences) {
        try {
            // Get ALL object properties in the ontology
            Set<OWLObjectProperty> properties = ontology.getObjectPropertiesInSignature();

            for (OWLObjectProperty property : properties) {
                // Get INFERRED values from reasoner (for query generation)
                Set<OWLNamedIndividual> inferredValues = reasoningService.getReasoner()
                        .getObjectPropertyValues(individual, property).getFlattened();

                // Get ASSERTED values from ontology (for comparison)
                Set<OWLNamedIndividual> assertedValues = ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)
                        .stream()
                        .filter(axiom -> axiom.getSubject().equals(individual) &&
                                axiom.getProperty().equals(property))
                        .map(axiom -> axiom.getObject())
                        .filter(obj -> obj instanceof OWLNamedIndividual)
                        .map(obj -> (OWLNamedIndividual) obj)
                        .collect(Collectors.toSet());

                // Process INFERRED values for queries
                for (OWLNamedIndividual inferredValue : inferredValues) {
                    String tripleKey = OntologyUtils.createTripleKey(
                            OntologyUtils.getShortForm(individual),
                            OntologyUtils.getShortForm(property),
                            OntologyUtils.getShortForm(inferredValue)
                    );

                    // Generate explanations: "How could this inferred property assertion be derived?"
                    Set<ExplanationPath> paths = explanationService.findPropertyAssertionPaths(
                            individual, property, inferredValue);

                    if (!paths.isEmpty()) {
                        inferences.put(tripleKey, paths);

                        boolean isAsserted = assertedValues.contains(inferredValue);
                        LOGGER.debug("Found {} explanation paths for {} property: {} {} {} (asserted: {})",
                                paths.size(),
                                isAsserted ? "ASSERTED" : "INFERRED",
                                OntologyUtils.getShortForm(individual),
                                OntologyUtils.getShortForm(property),
                                OntologyUtils.getShortForm(inferredValue),
                                isAsserted);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error extracting property assertions for {}: {}", individual, e.getMessage());
        }
    }

    private void processAndWriteInferences(Map<String, Set<ExplanationPath>> inferences,
                                           OWLOntology ontology, int tboxSize, int aboxSize,
                                           String rootEntity, ProcessingResult result) {
        List<InferenceCandidate> entailedOnly = inferences.entrySet().stream()
            .map(entry -> new InferenceCandidate(
                    entry.getKey(),
                    entry.getValue(),
                    EntailmentLabel.ENTAILED,
                    ontology))
            .collect(Collectors.toList());
        processAndWriteInferences(entailedOnly, ontology, tboxSize, aboxSize, rootEntity, "",
                Collections.emptyMap(), result);
    }

    private void processAndWriteInferences(List<InferenceCandidate> inferences,
                           OWLOntology ontology, int tboxSize, int aboxSize,
                           String rootEntity, String entailedOntologyPath,
                           Map<InferenceCandidate, String> nonEntailedOntologyPaths,
                           ProcessingResult result) {

        LOGGER.debug("Processing and writing {} inferences immediately", inferences.size());

        String ontologyName = extractOntologyName(ontology);

        // Group inferences by subject-predicate for MC queries
        Map<String, Set<ExplanationPath>> entailedInferenceMap = inferences.stream()
            .filter(candidate -> candidate.label == EntailmentLabel.ENTAILED)
            .collect(Collectors.toMap(
                candidate -> candidate.tripleKey,
                candidate -> candidate.paths,
                (left, right) -> left
            ));
        Map<String, Map<String, Set<String>>> subjectPredicateObjects = groupInferencesForMCQueries(entailedInferenceMap);

        long binaryQueries = 0;
        long multiChoiceQueries = 0;
        for (InferenceCandidate candidate : inferences) {
            String tripleKey = candidate.tripleKey;
            Set<ExplanationPath> paths = candidate.paths;
            EntailmentLabel label = candidate.label;

            try {
                String[] parts = OntologyUtils.parseTripleKey(tripleKey);
                if (parts.length != 3) continue;

                String subject = parts[0];
                String predicate = parts[1];
                String object = parts[2];
                String trackingKey = tripleKey + "::" + label.name();

                // CRITICAL: Check if this query was already processed globally
                if (!GlobalQueryTracker.markQueryProcessed(trackingKey, ontologyName)) {
                    LOGGER.debug("Skipping duplicate query: {} (first seen in {})",
                        trackingKey, GlobalQueryTracker.getFirstOntology(trackingKey));
                    continue;
                }

                String baseTaskType = "rdf:type".equals(predicate) ? "Membership" : "Property Assertion";
                String taskType = baseTaskType + " [" + label.name() + "]";

                // Calculate tag statistics instead of explanation statistics
                int[] tagStats = calculateTagStats(paths);

                String expectedBinaryAnswer;
                if (label == EntailmentLabel.ENTAILED) {
                    expectedBinaryAnswer = "TRUE";
                } else {
                    expectedBinaryAnswer = "UNKNOWN";
                }

                // 2. Write binary query (BIN) - ASK query
                String binaryTaskId = URIUtils.generateTaskId(rootEntity, subject, predicate,
                    "BIN_" + label.name());
                GlobalQueryTracker.addTaskId(trackingKey, binaryTaskId);

                String ontologyPath;
                if (label == EntailmentLabel.ENTAILED) {
                    ontologyPath = entailedOntologyPath;
                } else {
                    ontologyPath = nonEntailedOntologyPaths.getOrDefault(candidate, "");
                }

                String binaryQuery = String.format("ASK WHERE { <%s> <%s> <%s> }",
                        URIUtils.getFullURI(subject), URIUtils.getFullURI(predicate), URIUtils.getFullURI(object));

                outputService.writeComprehensiveQuery(
                        binaryTaskId, rootEntity, ontologyPath, tboxSize, aboxSize, taskType, "BIN",
                        binaryQuery, label.name(), predicate,
                        expectedBinaryAnswer, null, tagStats[0], tagStats[1]
                );
                binaryQueries++;

                // Generate MC only for entailed candidates.
                if (label == EntailmentLabel.ENTAILED &&
                    shouldGenerateMultiChoiceQuery(subject, predicate, subjectPredicateObjects)) {
                    Set<String> allObjectsSet = subjectPredicateObjects.get(subject).get(predicate);
                    List<String> allAnswers = allObjectsSet.stream()
                            .sorted()
                            .collect(Collectors.toList());

                    String multiTaskId = URIUtils.generateTaskId(rootEntity, subject, predicate, "MC");
                    GlobalQueryTracker.addTaskId(trackingKey, multiTaskId);

                    // MC query is SELECT - doesn't specify the object
                    String multiQuery = String.format("SELECT ?x WHERE { <%s> <%s> ?x }",
                            URIUtils.getFullURI(subject), URIUtils.getFullURI(predicate));

                    outputService.writeComprehensiveQuery(
                            multiTaskId, rootEntity, ontologyPath, tboxSize, aboxSize, taskType, "MC",
                            multiQuery, label.name(), predicate,
                            object, allAnswers,
                            tagStats[0], tagStats[1]  // Updated to use tag stats
                    );
                    multiChoiceQueries++;
                }

                // 1. Write comprehensive explanation to JSON AFTER generating task IDs
                if (label == EntailmentLabel.ENTAILED) {
                    String comprehensiveExplanation = ExplanationFormatter.generateExactJSONFormat(
                        tripleKey, paths, tagger);
                    outputService.writeExplanationWithComprehensiveFormat(tripleKey, comprehensiveExplanation);
                }

            } catch (Exception e) {
                LOGGER.warn("Error processing inference {}: {}", tripleKey, e.getMessage());
                result.addWarning("Failed to process inference: " + tripleKey);
            }
        }

        // Update counters and flush
        totalBinaryQueries.addAndGet(binaryQueries);
        totalMultiChoiceQueries.addAndGet(multiChoiceQueries);
        totalQueriesGenerated.addAndGet(binaryQueries + multiChoiceQueries);

        LOGGER.debug("Wrote {} binary queries and {} MC queries for ontology {}",
                binaryQueries, multiChoiceQueries, ontologyName);

        outputService.flush();
    }

    // Build each NON_ENTAILED example from the justification set for its own target
    private InferenceBuildResult buildInferenceCandidates(OWLOntology ontology,
                                                          Map<String, Set<ExplanationPath>> entailedInferences,
                                                          ProcessingResult result) {
        List<InferenceCandidate> candidates = new ArrayList<>();

        // Keep all original entailed candidates.
        for (Map.Entry<String, Set<ExplanationPath>> entry : entailedInferences.entrySet()) {
            candidates.add(new InferenceCandidate(
                    entry.getKey(),
                    entry.getValue(),
                    EntailmentLabel.ENTAILED,
                    ontology));
        }

        if (entailedInferences.isEmpty()) {
            return new InferenceBuildResult(candidates);
        }

        for (Map.Entry<String, Set<ExplanationPath>> entry : entailedInferences.entrySet()) {
            String tripleKey = entry.getKey();
            Set<ExplanationPath> paths = entry.getValue();

            try {
                OWLAxiom targetAxiom = buildAxiomFromTriple(
                        tripleKey,
                        ontology.getOWLOntologyManager().getOWLDataFactory());
                if (targetAxiom == null) {
                    continue;
                }

                Optional<RemovalVariant> removalVariant =
                        buildNonEntailedVariant(ontology, paths, targetAxiom);
                if (removalVariant.isPresent()) {
                    RemovalVariant variant = removalVariant.get();
                    candidates.add(new InferenceCandidate(
                            tripleKey,
                            paths,
                            EntailmentLabel.NON_ENTAILED,
                            variant.ontology,
                            variant.removedAxiom,
                            variant.strategy));
                }
            } catch (Exception e) {
                result.addWarning("Could not build non-entailed variant for " + tripleKey + ": " + e.getMessage());
            }
        }

        return new InferenceBuildResult(candidates);
    }

    private Optional<RemovalVariant> buildNonEntailedVariant(OWLOntology ontology,
                                                             Set<ExplanationPath> paths,
                                                             OWLAxiom targetAxiom) {
        if (paths == null || paths.isEmpty()) {
            return Optional.empty();
        }

        List<RankedRemovalAxiom> rankedAxioms = selectRankedRemovalAxioms(paths, targetAxiom);
        if (rankedAxioms.isEmpty()) {
            return Optional.empty();
        }

        int totalCandidateCount = rankedAxioms.size();
        rankedAxioms = limitRankedAxioms(rankedAxioms);
        int testedCandidates = 0;

        for (RankedRemovalAxiom rankedAxiom : rankedAxioms) {
            OWLAxiom axiom = rankedAxiom.axiom;
            if (!ontology.containsAxiom(axiom)) {
                continue;
            }

            testedCandidates++;
            OWLOntology trialOntology = cloneOntologyWithoutAxiom(ontology, axiom);
            if (doesNotEntail(trialOntology, targetAxiom)) {
                String strategy = buildRemovalStrategy(paths.size(), rankedAxiom, testedCandidates, totalCandidateCount);
                return Optional.of(new RemovalVariant(trialOntology, axiom, strategy));
            }
        }

        return Optional.empty();
    }

    private List<RankedRemovalAxiom> selectRankedRemovalAxioms(Set<ExplanationPath> paths, OWLAxiom targetAxiom) {
        String mode = getRemovalSearchMode();
        if ("exhaustive".equals(mode)) {
            return deterministicSortRemovableAxioms(getDistinctPathAxioms(paths)).stream()
                    .map(axiom -> new RankedRemovalAxiom(
                            axiom,
                            countPathFrequency(paths, axiom),
                            calculateTargetSignatureOverlap(axiom, targetAxiom),
                            calculateAxiomTypePriority(axiom),
                            isPresentInAllPaths(paths, axiom)))
                    .collect(Collectors.toList());
        }

        return rankRemovableAxioms(paths, targetAxiom);
    }

    private List<RankedRemovalAxiom> rankRemovableAxioms(Set<ExplanationPath> paths, OWLAxiom targetAxiom) {
        if (paths == null || paths.isEmpty()) {
            return Collections.emptyList();
        }

        Map<OWLAxiom, Integer> frequency = new HashMap<>();
        int pathCount = 0;
        for (ExplanationPath path : paths) {
            Set<OWLAxiom> uniquePathAxioms = getPathAxioms(path).stream()
                    .filter(Objects::nonNull)
                    .filter(axiom -> !axiom.isOfType(AxiomType.DECLARATION))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (uniquePathAxioms.isEmpty()) {
                continue;
            }

            pathCount++;
            for (OWLAxiom axiom : uniquePathAxioms) {
                frequency.merge(axiom, 1, Integer::sum);
            }
        }

        final int totalPaths = pathCount;
        return frequency.entrySet().stream()
                .map(entry -> new RankedRemovalAxiom(
                        entry.getKey(),
                        entry.getValue(),
                        calculateTargetSignatureOverlap(entry.getKey(), targetAxiom),
                        calculateAxiomTypePriority(entry.getKey()),
                        totalPaths > 0 && entry.getValue() == totalPaths))
                .sorted(Comparator
                        .comparingInt((RankedRemovalAxiom ranked) -> ranked.frequency).reversed()
                        .thenComparing((RankedRemovalAxiom ranked) -> ranked.presentInAllPaths, Comparator.reverseOrder())
                        .thenComparingInt((RankedRemovalAxiom ranked) -> ranked.targetSignatureOverlap).reversed()
                        .thenComparingInt((RankedRemovalAxiom ranked) -> ranked.axiomTypePriority).reversed()
                        .thenComparing(ranked -> sha256Hex(ranked.axiom.toString()))
                        .thenComparing(ranked -> ranked.axiom.toString()))
                .collect(Collectors.toList());
    }

    private List<RankedRemovalAxiom> limitRankedAxioms(List<RankedRemovalAxiom> rankedAxioms) {
        if ("exhaustive".equals(getRemovalSearchMode())) {
            return rankedAxioms;
        }

        int maxCandidates = config.getMaxRemovalCandidates();
        if (maxCandidates <= 0 || rankedAxioms.size() <= maxCandidates) {
            return rankedAxioms;
        }

        return new ArrayList<>(rankedAxioms.subList(0, maxCandidates));
    }

    private String buildRemovalStrategy(int pathCount, RankedRemovalAxiom rankedAxiom,
                                        int testedCandidates, int totalCandidateCount) {
        return String.format(
                "%s(paths=%d,frequency=%d,in_all_paths=%s,overlap=%d,tested=%d/%d)",
                getRemovalSearchMode(),
                pathCount,
                rankedAxiom.frequency,
                rankedAxiom.presentInAllPaths,
                rankedAxiom.targetSignatureOverlap,
                testedCandidates,
                totalCandidateCount);
    }

    private String getRemovalSearchMode() {
        String mode = config.getRemovalSearchMode();
        if (mode == null || mode.isBlank()) {
            return "frequency";
        }

        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
        if ("exhaustive".equals(normalizedMode) || "frequency".equals(normalizedMode)
                || "ranked".equals(normalizedMode)) {
            return normalizedMode;
        }

        LOGGER.warn("Unknown removal search mode '{}'; falling back to frequency", mode);
        return "frequency";
    }

    private Set<OWLAxiom> getDistinctPathAxioms(Set<ExplanationPath> paths) {
        if (paths == null || paths.isEmpty()) {
            return Collections.emptySet();
        }

        Set<OWLAxiom> axioms = new LinkedHashSet<>();
        for (ExplanationPath path : paths) {
            for (OWLAxiom axiom : getPathAxioms(path)) {
                if (axiom != null && !axiom.isOfType(AxiomType.DECLARATION)) {
                    axioms.add(axiom);
                }
            }
        }
        return axioms;
    }

    private int countPathFrequency(Set<ExplanationPath> paths, OWLAxiom targetAxiom) {
        if (paths == null || targetAxiom == null) {
            return 0;
        }

        int frequency = 0;
        for (ExplanationPath path : paths) {
            if (getPathAxioms(path).contains(targetAxiom)) {
                frequency++;
            }
        }
        return frequency;
    }

    private boolean isPresentInAllPaths(Set<ExplanationPath> paths, OWLAxiom axiom) {
        if (paths == null || paths.isEmpty() || axiom == null) {
            return false;
        }

        for (ExplanationPath path : paths) {
            if (!getPathAxioms(path).contains(axiom)) {
                return false;
            }
        }
        return true;
    }

    private int calculateTargetSignatureOverlap(OWLAxiom candidateAxiom, OWLAxiom targetAxiom) {
        if (candidateAxiom == null || targetAxiom == null) {
            return 0;
        }

        int overlap = 0;
        overlap += countOverlap(candidateAxiom.getClassesInSignature(), targetAxiom.getClassesInSignature());
        overlap += countOverlap(candidateAxiom.getObjectPropertiesInSignature(), targetAxiom.getObjectPropertiesInSignature());
        overlap += countOverlap(candidateAxiom.getDataPropertiesInSignature(), targetAxiom.getDataPropertiesInSignature());
        overlap += countOverlap(candidateAxiom.getIndividualsInSignature(), targetAxiom.getIndividualsInSignature());
        return overlap;
    }

    private <T> int countOverlap(Set<T> left, Set<T> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0;
        }

        int overlap = 0;
        for (T value : left) {
            if (right.contains(value)) {
                overlap++;
            }
        }
        return overlap;
    }

    private int calculateAxiomTypePriority(OWLAxiom axiom) {
        if (axiom instanceof OWLClassAssertionAxiom || axiom instanceof OWLObjectPropertyAssertionAxiom) {
            return 3;
        }
        if (OntologyUtils.isTBoxAxiom(axiom)) {
            return 2;
        }
        return 1;
    }

    private Set<OWLAxiom> getJustificationIntersection(Set<ExplanationPath> paths) {
        Iterator<ExplanationPath> iterator = paths.iterator();
        if (!iterator.hasNext()) {
            return Collections.emptySet();
        }

        Set<OWLAxiom> intersection = new LinkedHashSet<>(getPathAxioms(iterator.next()));
        while (iterator.hasNext()) {
            intersection.retainAll(getPathAxioms(iterator.next()));
            if (intersection.isEmpty()) {
                return Collections.emptySet();
            }
        }

        return intersection;
    }

    private Collection<OWLAxiom> getPathAxioms(ExplanationPath path) {
        if (path == null || path.getAxioms() == null) {
            return Collections.emptySet();
        }
        return path.getAxioms();
    }

    private List<OWLAxiom> deterministicSortRemovableAxioms(Collection<OWLAxiom> axioms) {
        if (axioms == null || axioms.isEmpty()) {
            return Collections.emptyList();
        }

        return axioms.stream()
                .filter(Objects::nonNull)
                .filter(axiom -> !axiom.isOfType(AxiomType.DECLARATION))
                .distinct()
                .sorted(Comparator
                        .comparing((OWLAxiom axiom) -> sha256Hex(axiom.toString()))
                        .thenComparing(OWLAxiom::toString))
                .collect(Collectors.toList());
    }

    private OWLOntology cloneOntology(OWLOntology sourceOntology) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            return manager.createOntology(sourceOntology.getAxioms());
        } catch (OWLOntologyCreationException e) {
            throw new RuntimeException("Failed to clone ontology", e);
        }
    }

    // Clone the ontology and remove the specified axiom to create a trial version for testing.
    private OWLOntology cloneOntologyWithoutAxiom(OWLOntology sourceOntology, OWLAxiom axiomToRemove) {
        OWLOntology clone = cloneOntology(sourceOntology);
        clone.getOWLOntologyManager().removeAxiom(clone, axiomToRemove);
        return clone;
    }

    // Build an axiom from tripple takes subject-predicate-object and turns it into corresponding OWL axiom that the reasoner can test
    private OWLAxiom buildAxiomFromTriple(String tripleKey, OWLDataFactory dataFactory) {
        String[] parts = OntologyUtils.parseTripleKey(tripleKey);
        if (parts.length != 3) {
            return null;
        }

        String subject = parts[0];
        String predicate = parts[1];
        String object = parts[2];

        OWLNamedIndividual subjectInd = dataFactory.getOWLNamedIndividual(IRI.create(URIUtils.getFullURI(subject)));

        if ("rdf:type".equals(predicate)) {
            OWLClass objectClass = dataFactory.getOWLClass(IRI.create(URIUtils.getFullURI(object)));
            return dataFactory.getOWLClassAssertionAxiom(objectClass, subjectInd);
        }

        OWLObjectProperty property = dataFactory.getOWLObjectProperty(IRI.create(URIUtils.getFullURI(predicate)));
        OWLNamedIndividual objectInd = dataFactory.getOWLNamedIndividual(IRI.create(URIUtils.getFullURI(object)));
        return dataFactory.getOWLObjectPropertyAssertionAxiom(property, subjectInd, objectInd);
    }

    // Check if the target axiom is still entailed in the mutated ontology.
    private boolean isEntailed(OWLOntology ontology, OWLAxiom targetAxiom) {
        PelletReasoningService tempReasoner = new PelletReasoningService();
        try {
            tempReasoner.initializeReasoner(ontology);
            if (!tempReasoner.isConsistent()) {
                return false;
            }
            return tempReasoner.isEntailed(targetAxiom);
        } finally {
            tempReasoner.close();
        }
    }

    private boolean doesNotEntail(OWLOntology ontology, OWLAxiom targetAxiom) {
        PelletReasoningService tempReasoner = new PelletReasoningService(getRemovalCandidateTimeoutMillis());
        try {
            tempReasoner.initializeReasoner(ontology);
            return !tempReasoner.isEntailed(targetAxiom);
        } catch (RuntimeException e) {
            LOGGER.debug("Skipping removal candidate because entailment verification failed or timed out: {}",
                    e.getMessage());
            return false;
        } finally {
            tempReasoner.close();
        }
    }

    private long getRemovalCandidateTimeoutMillis() {
        int timeoutSeconds = config.getRemovalCandidateTimeoutSeconds();
        return timeoutSeconds <= 0 ? 0 : timeoutSeconds * 1000L;
    }

    // Helper method to extract ontology name
    private String extractOntologyName(OWLOntology ontology) {
        try {
            Optional<IRI> ontologyIRI = ontology.getOntologyID().getOntologyIRI();
            if (ontologyIRI.isPresent()) {
                return URIUtils.getLocalName(ontologyIRI.get().toString());
            }
        } catch (Exception e) {
            LOGGER.debug("Could not extract ontology name: {}", e.getMessage());
        }
        return "unknown";
    }

    private Map<InferenceCandidate, String> storeNonEntailedOntologyVariants(
            List<InferenceCandidate> candidates,
            String rootEntity,
            ProcessingResult result) {
        Map<InferenceCandidate, String> ontologyPaths = new IdentityHashMap<>();

        for (InferenceCandidate candidate : candidates) {
            if (candidate.label != EntailmentLabel.NON_ENTAILED || candidate.variantOntology == null) {
                continue;
            }

            String fileName = buildNonEntailedOntologyFileName(rootEntity, candidate);
            saveOntologyVariant(candidate.variantOntology, EntailmentLabel.NON_ENTAILED, fileName, result);
            ontologyPaths.put(candidate, buildOntologyRelativePath(EntailmentLabel.NON_ENTAILED, fileName));
        }

        return ontologyPaths;
    }

    private void storeRemovedAxioms(List<InferenceCandidate> candidates,
                                    Map<InferenceCandidate, String> nonEntailedOntologyPaths,
                                    String rootEntity,
                                    ProcessingResult result) {
        File outputDirectory = new File(
                config.getOutputDirectory(),
                "removed_axioms" + File.separator + EntailmentLabel.NON_ENTAILED.name());

        for (InferenceCandidate candidate : candidates) {
            if (candidate.label != EntailmentLabel.NON_ENTAILED || candidate.removedAxiom == null) {
                continue;
            }

            if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                result.addWarning("Could not create removed axioms directory: " + outputDirectory.getAbsolutePath());
                return;
            }

            File outputFile = new File(
                    outputDirectory,
                    buildRemovedAxiomFileName(rootEntity, candidate));

            try (FileWriter writer = new FileWriter(outputFile, false)) {
                writer.write("# Removed axiom for NON_ENTAILED ontology\n");
                writer.write("# Root entity: " + rootEntity + "\n");
                writer.write("# Target entailment: " + candidate.tripleKey + "\n");
                writer.write("# Strategy: " + candidate.removalStrategy + "\n");
                writer.write("# Ontology path: " + nonEntailedOntologyPaths.getOrDefault(candidate, "") + "\n\n");
                writer.write("# Removed axiom SHA-256: " + sha256Hex(candidate.removedAxiom.toString()) + "\n");
                writer.write("# Variant decision SHA-256: " + sha256Hex(buildVariantHashInput(candidate)) + "\n\n");
                writer.write("1. " + candidate.removedAxiom + System.lineSeparator());
            } catch (IOException e) {
                result.addWarning("Could not save removed axiom for " + candidate.tripleKey + ": " + e.getMessage());
            }
        }
    }

    private void saveOntologyVariant(OWLOntology ontology, EntailmentLabel label, String fileName,
                                     ProcessingResult result) {
        File labelDirectory = new File(config.getOutputDirectory(), "ontologies" + File.separator + label.name());
        if (!labelDirectory.exists() && !labelDirectory.mkdirs()) {
            result.addWarning("Could not create ontology output directory: " + labelDirectory.getAbsolutePath());
            return;
        }

        File outputFile = new File(labelDirectory, fileName);
        if (outputFile.exists()) {
            return;
        }

        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            ontology.getOWLOntologyManager().saveOntology(ontology, new TurtleDocumentFormat(), outputStream);
        } catch (OWLOntologyStorageException | IOException e) {
            result.addWarning("Could not save " + label.name() + " ontology '" + fileName + "': " + e.getMessage());
        }
    }

    private String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "ontology";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String buildOntologyRelativePath(EntailmentLabel label, String fileName) {
        return "ontologies/" + label.name() + "/" + fileName;
    }

    private String buildOntologyFileName(String taskId) {
        return sanitizeFileName(taskId) + ".ttl";
    }

    private String buildNonEntailedOntologyFileName(String rootEntity, InferenceCandidate candidate) {
        return buildVariantFileStem(rootEntity, candidate) + ".ttl";
    }

    private String buildRemovedAxiomFileName(String rootEntity, InferenceCandidate candidate) {
        return buildVariantFileStem(rootEntity, candidate) + "_removed_axiom.txt";
    }

    private String buildVariantFileStem(String rootEntity, InferenceCandidate candidate) {
        String[] parts = OntologyUtils.parseTripleKey(candidate.tripleKey);
        String readableTriple = parts.length == 3
                ? parts[0] + "_" + parts[1] + "_" + parts[2]
                : candidate.tripleKey;
        String stem = sanitizeFileName(rootEntity + "__non_entailed__" + readableTriple);
        int maxReadableLength = 140;
        if (stem.length() > maxReadableLength) {
            stem = stem.substring(0, maxReadableLength);
        }
        return sanitizeFileName(stem);
    }

    private String buildVariantHashInput(InferenceCandidate candidate) {
        return String.join("|",
                "NON_ENTAILED",
                nullToEmpty(candidate.tripleKey),
                nullToEmpty(candidate.removalStrategy),
                candidate.removedAxiom == null ? "" : candidate.removedAxiom.toString());
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String storeEntailedOntology(OWLOntology ontology, File ontologyFile, String rootEntity,
                                         ProcessingResult result) {
        String fileName = sanitizeFileName(rootEntity + "__original_1hop") + getOntologyExtension(ontologyFile.getName());
        saveOntologyVariant(ontology, EntailmentLabel.ENTAILED, fileName, result);
        return buildOntologyRelativePath(EntailmentLabel.ENTAILED, fileName);
    }

    private String getOntologyExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot);
        }
        return ".ttl";
    }

    // Updated to check against subject-predicate combinations
    private boolean shouldGenerateMultiChoiceQuery(String subject, String predicate,
                                                   Map<String, Map<String, Set<String>>> subjectPredicateObjects) {
        Map<String, Set<String>> predicateObjects = subjectPredicateObjects.get(subject);
        if (predicateObjects == null) return false;

        Set<String> objects = predicateObjects.get(predicate);
        return objects != null && objects.size() > 1; // Only generate MC if multiple objects
    }

    /**
     * UPDATED: Extract root entity directly from TTL filename
     */
    private String extractRootEntityFromFilename(File ontologyFile) {
        try {
            String fileName = ontologyFile.getName();

            // Remove file extension (.ttl, .owl, .rdf, .n3)
            String rootEntity = fileName.replaceAll("\\.(ttl|owl|rdf|n3)$", "");

            LOGGER.debug("Extracted root entity '{}' from filename '{}'", rootEntity, fileName);
            return rootEntity;

        } catch (Exception e) {
            LOGGER.warn("Could not extract root entity from filename {}: {}",
                    ontologyFile.getName(), e.getMessage());

            // Fallback: use filename without extension
            String fileName = ontologyFile.getName();
            int lastDot = fileName.lastIndexOf('.');
            return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
        }
    }

    // NEW METHOD - Classify task type based on predicate
    private String getTaskType(String predicate) {
        if ("rdf:type".equals(predicate)) {
            return "Membership";
        } else {
            return "Property Assertion";
        }
    }

    /**
     * NEW: Group inferences by subject-predicate to prepare for MC queries
     */
    private Map<String, Map<String, Set<String>>> groupInferencesForMCQueries(
            Map<String, Set<ExplanationPath>> inferences) {

        Map<String, Map<String, Set<String>>> grouped = new HashMap<>();

        for (String tripleKey : inferences.keySet()) {
            String[] parts = OntologyUtils.parseTripleKey(tripleKey);
            if (parts.length != 3) continue;

            String subject = parts[0];
            String predicate = parts[1];
            String object = parts[2];

            grouped.computeIfAbsent(subject, k -> new HashMap<>())
                    .computeIfAbsent(predicate, k -> new HashSet<>())
                    .add(object);
        }

        return grouped;
    }

    /**
     * Calculate tag length statistics from explanation paths
     */
    private int[] calculateTagStats(Set<ExplanationPath> paths) {
        if (paths.isEmpty()) {
            return new int[]{0, 0};
        }

        int minTagLength = Integer.MAX_VALUE;
        int maxTagLength = 0;

        for (ExplanationPath path : paths) {
            String tag = tagger.tagExplanation(path);
            int tagLength = tag != null ? tag.length() : 0;

            minTagLength = Math.min(minTagLength, tagLength);
            maxTagLength = Math.max(maxTagLength, tagLength);
        }

        // Handle case where all paths have empty tags
        if (minTagLength == Integer.MAX_VALUE) {
            minTagLength = 0;
        }

        return new int[]{minTagLength, maxTagLength};
    }

    /**
     * NEW: Clean up resources after processing each ontology
     */
    private void cleanupResources(ComprehensiveExplanationService explanationService) {
        try {
            if (reasoningService != null) {
                reasoningService.close(); // This disposes the reasoner
            }
        } catch (Exception e) {
            LOGGER.warn("Error closing reasoning service", e);
        }

        explanationService = null;
        System.gc(); // Force garbage collection
    }

    /**
     * NEW: Log current memory usage
     */
    private void logMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        LOGGER.info("Memory Usage: {:.2f} MB used, {:.2f} MB free, {:.2f} MB total, {:.2f} MB max",
                usedMemory / (1024.0 * 1024.0), freeMemory / (1024.0 * 1024.0),
                totalMemory / (1024.0 * 1024.0), maxMemory / (1024.0 * 1024.0));
    }

    /**
     * UPDATED: Finalize results with new counters
     */
    private void finalizeResults(ProcessingResult result) {
        result.setProcessingTimeMs(performanceTracker.getDuration("total_processing"));
        result.setTotalInferences(totalInferencesProcessed.get());
        result.addProcessedQueries(totalQueriesGenerated.get());
        result.setBinaryQueries(totalBinaryQueries.get());
        result.setMultiChoiceQueries(totalMultiChoiceQueries.get());

        // Calculate memory usage
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        result.setMemoryUsedMB(usedMemory / (1024.0 * 1024.0));

        result.setSuccess(true);

        LOGGER.info("Sequential processing completed successfully!");
        LOGGER.info("Final Statistics:");
        LOGGER.info("  Processed ontologies: {}", totalOntologiesProcessed.get());
        LOGGER.info("  Total inferences: {}", totalInferencesProcessed.get());
        LOGGER.info("  Total queries generated: {}", totalQueriesGenerated.get());
        LOGGER.info("  Binary queries: {}", totalBinaryQueries.get());
        LOGGER.info("  Multi-choice queries: {}", totalMultiChoiceQueries.get());
    }

    @Override
    public void close() throws Exception {
        LOGGER.info("Closing SmallOntologiesProcessor...");

        // Close services
        try {
            if (outputService != null) {
                outputService.close();
            }
        } catch (Exception e) {
            LOGGER.warn("Error closing output service", e);
        }

        try {
            if (reasoningService != null) {
                reasoningService.close();
            }
        } catch (Exception e) {
            LOGGER.warn("Error closing reasoning service", e);
        }

        try {
            if (ontologyService != null) {
                ontologyService.close();
            }
        } catch (Exception e) {
            LOGGER.warn("Error closing ontology service", e);
        }

        LOGGER.info("SmallOntologiesProcessor closed successfully");
    }
}
