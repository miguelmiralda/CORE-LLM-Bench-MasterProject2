package com.example.processing;

import com.example.config.ProcessingConfiguration;
import com.example.evaluation.AccuracyReportService;
import com.example.evaluation.AccuracyReportService.Subset;
import com.example.evaluation.LlmEvaluationService;
import com.example.explanation.ComprehensiveExplanationService;
import com.example.explanation.EnhancedExplanationTagger;
import com.example.explanation.ExplanationFormatter;
import com.example.explanation.ExplanationPath;
import com.example.ontology.OntologyService;
import com.example.output.OutputService;
import com.example.query.NaturalLanguageVerbalisationService;
import com.example.query.NaturalLanguageVerbalisationService.TranslationStrategy;
import com.example.query.QueryGenerationService;
import com.example.reasoning.ReasoningService;
import com.example.util.OntologyUtils;
import com.example.util.URIUtils;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * IMPROVED: Sequential processor for handling multiple small ontologies
 * Processes one ontology at a time to prevent memory issues
 */
public class SmallOntologiesProcessor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmallOntologiesProcessor.class);

    private final OntologyService ontologyService;
    private final ReasoningService reasoningService;
    private final QueryGenerationService queryService;
    private final OutputService outputService;
    private final ProcessingConfiguration config;
    private final EnhancedExplanationTagger tagger;
    private final PerformanceTracker performanceTracker;
    private final NaturalLanguageVerbalisationService verbalisationService;
    private final LlmEvaluationService llmService;
    private final AccuracyReportService accuracyReport;

    private final AtomicLong totalOntologiesProcessed = new AtomicLong(0);
    private final AtomicLong totalInferencesProcessed = new AtomicLong(0);
    private final AtomicLong totalQueriesGenerated = new AtomicLong(0);
    private final AtomicLong totalBinaryQueries = new AtomicLong(0);
    private final AtomicLong totalMultiChoiceQueries = new AtomicLong(0);

    private Set<String> currentOntologyMCQueries;

    private int tboxSize = 0;
    private int aboxSize = 0;

    public SmallOntologiesProcessor(OntologyService ontologyService,
                                    ReasoningService reasoningService,
                                    QueryGenerationService queryService,
                                    OutputService outputService,
                                    ProcessingConfiguration config,
                                    LlmEvaluationService llmService,
                                    AccuracyReportService accuracyReport) {
        this.ontologyService = ontologyService;
        this.reasoningService = reasoningService;
        this.queryService = queryService;
        this.outputService = outputService;
        this.config = config;
        this.llmService = llmService;
        this.accuracyReport = accuracyReport;
        this.verbalisationService = new NaturalLanguageVerbalisationService();
        this.tagger = new EnhancedExplanationTagger();
        this.performanceTracker = new PerformanceTracker();

        LOGGER.info("SmallOntologiesProcessor initialized for SEQUENTIAL processing");
    }

    public ProcessingResult processSmallOntologies(String ontologiesDirectory) {
        ProcessingResult result = new ProcessingResult();
        performanceTracker.start("total_processing");

        try {
            LOGGER.info("Starting SEQUENTIAL processing of small ontologies from: {}", ontologiesDirectory);

            outputService.initialize();

            performanceTracker.start("file_discovery");
            List<File> ontologyFiles = discoverOntologyFiles(ontologiesDirectory);
            performanceTracker.end("file_discovery");

            LOGGER.info("Discovered {} ontology files", ontologyFiles.size());
            if (ontologyFiles.isEmpty()) {
                LOGGER.warn("No ontology files found in directory: {}", ontologiesDirectory);
                result.setError("No ontology files found in directory");
                return result;
            }

            performanceTracker.start("sequential_processing");
            processOntologyFilesSequentially(ontologyFiles, result);
            performanceTracker.end("sequential_processing");

            try {
                accuracyReport.writeReport(config.getOutputDirectory());
            } catch (Exception e) {
                LOGGER.error("Failed to write accuracy report", e);
                result.addWarning("Failed to write accuracy report: " + e.getMessage());
            }

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

    private void processOntologyFilesSequentially(List<File> ontologyFiles, ProcessingResult result) {
        for (int i = 0; i < ontologyFiles.size(); i++) {
            File ontologyFile = ontologyFiles.get(i);

            try {
                LOGGER.info("Processing file {}/{}: {}", i + 1, ontologyFiles.size(), ontologyFile.getName());

                boolean success = processSingleOntologyFile(ontologyFile, result);

                if (success) {
                    totalOntologiesProcessed.incrementAndGet();
                }

                System.gc();

                if ((i + 1) % 10 == 0) {
                    long processed = totalOntologiesProcessed.get();
                    double progressPct = (processed * 100.0) / ontologyFiles.size();
                    LOGGER.info("Progress: {}/{} files processed ({}%)",
                            processed, ontologyFiles.size(), String.format("%.1f", progressPct));
                    logMemoryUsage();
                }

            } catch (Exception e) {
                LOGGER.warn("Error processing ontology file {}: {}", ontologyFile.getName(), e.getMessage());
                result.addError("Failed to process file " + ontologyFile.getName() + ": " + e.getMessage());
            }
        }
    }

    private boolean processSingleOntologyFile(File ontologyFile, ProcessingResult result) {
        OWLOntology ontology = null;
        ComprehensiveExplanationService explanationService = null;

        try {
            currentOntologyMCQueries = new HashSet<>();

            ontology = ontologyService.loadOntology(ontologyFile);
            LOGGER.debug("Loaded ontology: {} with {} axioms",
                    ontologyFile.getName(), ontology.getAxiomCount());

            String rootEntity = extractRootEntityFromFilename(ontologyFile);
            LOGGER.debug("Extracted root entity from filename: {}", rootEntity);

            int calculatedTBoxSize = OntologyUtils.calculateTBoxSize(ontology);
            int calculatedABoxSize = OntologyUtils.calculateABoxSize(ontology);
            calculateOntologyStats(calculatedTBoxSize, calculatedABoxSize);

            reasoningService.initializeReasoner(ontology);

            if (!reasoningService.isConsistent()) {
                LOGGER.warn("Inconsistent ontology detected: {}", ontologyFile.getName());
                result.addWarning("Inconsistent ontology: " + ontologyFile.getName());
                return false;
            }

            explanationService = new ComprehensiveExplanationService(
                    reasoningService.getReasoner(), ontology);

            Map<String, Set<ExplanationPath>> ontologyInferences =
                    extractInferencesWithExplanations(ontology, explanationService);

            processAndWriteInferences(ontologyInferences, ontology, tboxSize, aboxSize, rootEntity, result);

            totalInferencesProcessed.addAndGet(ontologyInferences.size());
            return true;

        } catch (Exception e) {
            LOGGER.error("Error processing ontology file: {}", ontologyFile.getName(), e);
            return false;
        } finally {
            cleanupResources(explanationService);
        }
    }

    public synchronized void calculateOntologyStats(int tboxSize, int aboxSize) {
        this.tboxSize = tboxSize;
        this.aboxSize = aboxSize;
        LOGGER.info("Set ontology sizes - TBox: {}, ABox: {}", tboxSize, aboxSize);
    }

    private Map<String, Set<ExplanationPath>> extractInferencesWithExplanations(
            OWLOntology ontology, ComprehensiveExplanationService explanationService) {

        Map<String, Set<ExplanationPath>> inferences = new HashMap<>();
        Set<OWLNamedIndividual> individuals = ontology.getIndividualsInSignature();

        LOGGER.debug("Processing {} individuals from ontology", individuals.size());

        for (OWLNamedIndividual individual : individuals) {
            try {
                extractClassAssertionInferences(individual, ontology, explanationService, inferences);
                extractPropertyAssertionInferences(individual, ontology, explanationService, inferences);
            } catch (Exception e) {
                LOGGER.debug("Error processing individual {}: {}", individual, e.getMessage());
            }
        }

        LOGGER.debug("Extracted {} inferences from ontology", inferences.size());
        return inferences;
    }

    private void extractClassAssertionInferences(OWLNamedIndividual individual,
                                                 OWLOntology ontology,
                                                 ComprehensiveExplanationService explanationService,
                                                 Map<String, Set<ExplanationPath>> inferences) {
        try {
            Set<OWLClass> inferredTypes = reasoningService.getReasoner()
                    .getTypes(individual, false).getFlattened();

            for (OWLClass inferredClass : inferredTypes) {
                if (OntologyUtils.isOwlThing(inferredClass)) continue;

                String tripleKey = OntologyUtils.createTripleKey(
                        OntologyUtils.getShortForm(individual),
                        "rdf:type",
                        OntologyUtils.getShortForm(inferredClass)
                );

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

    private void extractPropertyAssertionInferences(OWLNamedIndividual individual,
                                                    OWLOntology ontology,
                                                    ComprehensiveExplanationService explanationService,
                                                    Map<String, Set<ExplanationPath>> inferences) {
        try {
            Set<OWLObjectProperty> properties = ontology.getObjectPropertiesInSignature();

            for (OWLObjectProperty property : properties) {
                Set<OWLNamedIndividual> inferredValues = reasoningService.getReasoner()
                        .getObjectPropertyValues(individual, property).getFlattened();

                Set<OWLNamedIndividual> assertedValues = ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)
                        .stream()
                        .filter(axiom -> axiom.getSubject().equals(individual) &&
                                axiom.getProperty().equals(property))
                        .map(axiom -> axiom.getObject())
                        .filter(obj -> obj instanceof OWLNamedIndividual)
                        .map(obj -> (OWLNamedIndividual) obj)
                        .collect(Collectors.toSet());

                for (OWLNamedIndividual inferredValue : inferredValues) {
                    String tripleKey = OntologyUtils.createTripleKey(
                            OntologyUtils.getShortForm(individual),
                            OntologyUtils.getShortForm(property),
                            OntologyUtils.getShortForm(inferredValue)
                    );

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

        LOGGER.debug("Processing and writing {} inferences immediately", inferences.size());

        String ontologyName = extractOntologyName(ontology);
        Map<String, Map<String, Set<String>>> subjectPredicateObjects = groupInferencesForMCQueries(inferences);

        // Build lookup sets for negative generation
        Set<String> allEntailedTripleKeys = new HashSet<>(inferences.keySet());
        List<String> allIndividuals = ontology.getIndividualsInSignature().stream()
                .map(ind -> OntologyUtils.getShortForm(ind))
                .collect(Collectors.toList());
        List<String> allClasses = ontology.getClassesInSignature().stream()
                .filter(c -> !c.isOWLThing() && !c.isOWLNothing())
                .map(c -> OntologyUtils.getShortForm(c))
                .collect(Collectors.toList());
        Random rng = new Random(42);

        long binaryQueries = 0;
        long multiChoiceQueries = 0;

        for (Map.Entry<String, Set<ExplanationPath>> entry : inferences.entrySet()) {
            String tripleKey = entry.getKey();
            Set<ExplanationPath> paths = entry.getValue();

            try {
                String[] parts = OntologyUtils.parseTripleKey(tripleKey);
                if (parts.length != 3) continue;

                String subject = parts[0];
                String predicate = parts[1];
                String object = parts[2];

                if (!GlobalQueryTracker.markQueryProcessed(tripleKey, ontologyName)) {
                    LOGGER.debug("Skipping duplicate query: {} (first seen in {})",
                            tripleKey, GlobalQueryTracker.getFirstOntology(tripleKey));
                    continue;
                }

                String taskType = "rdf:type".equals(predicate) ? "Membership" : "Property Assertion";
                int[] tagStats = calculateTagStats(paths);

                String binaryTaskId = URIUtils.generateTaskId(rootEntity, subject, predicate, "BIN");
                GlobalQueryTracker.addTaskId(tripleKey, binaryTaskId);

                String binaryQuery = String.format("ASK WHERE { <%s> <%s> <%s> }",
                        URIUtils.getFullURI(subject), URIUtils.getFullURI(predicate), URIUtils.getFullURI(object));

                String verbDirect = verbalisationService.verbaliseBinaryQuery(subject, predicate, object, TranslationStrategy.DIRECT, ontology, paths);
                String verbContextual = verbalisationService.verbaliseBinaryQuery(subject, predicate, object, TranslationStrategy.CONTEXTUAL, ontology, paths);
                String verbRelational = verbalisationService.verbaliseBinaryQuery(subject, predicate, object, TranslationStrategy.RELATIONAL, ontology, paths);
                String verbFormal = verbalisationService.verbaliseBinaryQuery(subject, predicate, object, TranslationStrategy.FORMAL, ontology, paths);

                String llmDirect = llmService.askLlm(verbDirect, "BIN");
                String llmContextual = llmService.askLlm(verbContextual, "BIN");
                String llmRelational = llmService.askLlm(verbRelational, "BIN");
                String llmFormal = llmService.askLlm(verbFormal, "BIN");

                accuracyReport.record("DIRECT", Subset.ENTAILED, "TRUE", llmDirect, "BIN", verbalisationService.questionComplexity(verbDirect));
                accuracyReport.record("CONTEXTUAL", Subset.ENTAILED, "TRUE", llmContextual, "BIN", verbalisationService.questionComplexity(verbContextual));
                accuracyReport.record("RELATIONAL", Subset.ENTAILED, "TRUE", llmRelational, "BIN", verbalisationService.questionComplexity(verbRelational));
                accuracyReport.record("FORMAL", Subset.ENTAILED, "TRUE", llmFormal, "BIN", verbalisationService.questionComplexity(verbFormal));

                outputService.writeComprehensiveQuery(
                        binaryTaskId, rootEntity, tboxSize, aboxSize, taskType, "BIN", binaryQuery,
                        verbDirect, verbContextual, verbRelational, verbFormal,
                        llmDirect, llmContextual, llmRelational, llmFormal,
                        predicate, "TRUE", null, tagStats[0], tagStats[1]);
                binaryQueries++;

                // ── Non-entailed (negative) BIN question ──────────────────────────
                String negObject = pickNegativeObject(
                        subject, predicate, object, allEntailedTripleKeys,
                        allIndividuals, allClasses, rng);
                if (negObject != null) {
                    String negTaskId = URIUtils.generateTaskId(rootEntity, subject, predicate, "NEG");
                    String negQuery = String.format("ASK WHERE { <%s> <%s> <%s> }",
                            URIUtils.getFullURI(subject), URIUtils.getFullURI(predicate), URIUtils.getFullURI(negObject));

                    String negVerbDirect = verbalisationService.verbaliseBinaryQuery(subject, predicate, negObject, TranslationStrategy.DIRECT, ontology, null);
                    String negVerbContextual = verbalisationService.verbaliseBinaryQuery(subject, predicate, negObject, TranslationStrategy.CONTEXTUAL, ontology, null);
                    String negVerbRelational = verbalisationService.verbaliseBinaryQuery(subject, predicate, negObject, TranslationStrategy.RELATIONAL, ontology, null);
                    String negVerbFormal = verbalisationService.verbaliseBinaryQuery(subject, predicate, negObject, TranslationStrategy.FORMAL, ontology, null);

                    String negLlmDirect = llmService.askLlm(negVerbDirect, "BIN");
                    String negLlmContextual = llmService.askLlm(negVerbContextual, "BIN");
                    String negLlmRelational = llmService.askLlm(negVerbRelational, "BIN");
                    String negLlmFormal = llmService.askLlm(negVerbFormal, "BIN");

                    accuracyReport.record("DIRECT", Subset.NON_ENTAILED, "FALSE", negLlmDirect, "BIN", verbalisationService.questionComplexity(negVerbDirect));
                    accuracyReport.record("CONTEXTUAL", Subset.NON_ENTAILED, "FALSE", negLlmContextual, "BIN", verbalisationService.questionComplexity(negVerbContextual));
                    accuracyReport.record("RELATIONAL", Subset.NON_ENTAILED, "FALSE", negLlmRelational, "BIN", verbalisationService.questionComplexity(negVerbRelational));
                    accuracyReport.record("FORMAL", Subset.NON_ENTAILED, "FALSE", negLlmFormal, "BIN", verbalisationService.questionComplexity(negVerbFormal));

                    outputService.writeComprehensiveQuery(
                            negTaskId, rootEntity, tboxSize, aboxSize, taskType, "BIN", negQuery,
                            negVerbDirect, negVerbContextual, negVerbRelational, negVerbFormal,
                            negLlmDirect, negLlmContextual, negLlmRelational, negLlmFormal,
                            predicate, "FALSE", null, tagStats[0], tagStats[1]);
                    binaryQueries++;
                }

                if (shouldGenerateMultiChoiceQuery(subject, predicate, subjectPredicateObjects)) {
                    Set<String> allObjectsSet = subjectPredicateObjects.get(subject).get(predicate);
                    List<String> allAnswers = allObjectsSet.stream().sorted().collect(Collectors.toList());

                    String multiTaskId = URIUtils.generateTaskId(rootEntity, subject, predicate, "MC");
                    GlobalQueryTracker.addTaskId(tripleKey, multiTaskId);

                    String multiQuery = String.format("SELECT ?x WHERE { <%s> <%s> ?x }",
                            URIUtils.getFullURI(subject), URIUtils.getFullURI(predicate));

                    String verbMCDirect = verbalisationService.verbaliseMultiChoiceQuery(subject, predicate, TranslationStrategy.DIRECT, ontology, paths);
                    String verbMCContextual = verbalisationService.verbaliseMultiChoiceQuery(subject, predicate, TranslationStrategy.CONTEXTUAL, ontology, paths);
                    String verbMCRelational = verbalisationService.verbaliseMultiChoiceQuery(subject, predicate, TranslationStrategy.RELATIONAL, ontology, paths);
                    String verbMCFormal = verbalisationService.verbaliseMultiChoiceQuery(subject, predicate, TranslationStrategy.FORMAL, ontology, paths);

                    String llmMCDirect = llmService.askLlm(verbMCDirect, "MC");
                    String llmMCContextual = llmService.askLlm(verbMCContextual, "MC");
                    String llmMCRelational = llmService.askLlm(verbMCRelational, "MC");
                    String llmMCFormal = llmService.askLlm(verbMCFormal, "MC");

                    String groundTruthMC = String.join("; ", allAnswers);

                    accuracyReport.record("DIRECT", Subset.ENTAILED, groundTruthMC, llmMCDirect, "MC", verbalisationService.questionComplexity(verbMCDirect));
                    accuracyReport.record("CONTEXTUAL", Subset.ENTAILED, groundTruthMC, llmMCContextual, "MC", verbalisationService.questionComplexity(verbMCContextual));
                    accuracyReport.record("RELATIONAL", Subset.ENTAILED, groundTruthMC, llmMCRelational, "MC", verbalisationService.questionComplexity(verbMCRelational));
                    accuracyReport.record("FORMAL", Subset.ENTAILED, groundTruthMC, llmMCFormal, "MC", verbalisationService.questionComplexity(verbMCFormal));

                    outputService.writeComprehensiveQuery(
                            multiTaskId, rootEntity, tboxSize, aboxSize, taskType, "MC", multiQuery,
                            verbMCDirect, verbMCContextual, verbMCRelational, verbMCFormal,
                            llmMCDirect, llmMCContextual, llmMCRelational, llmMCFormal,
                            predicate, object, allAnswers, tagStats[0], tagStats[1]);
                    multiChoiceQueries++;
                }

                String comprehensiveExplanation = ExplanationFormatter.generateExactJSONFormat(
                        tripleKey, paths, tagger);
                outputService.writeExplanationWithComprehensiveFormat(tripleKey, comprehensiveExplanation);

            } catch (Exception e) {
                LOGGER.warn("Error processing inference {}: {}", tripleKey, e.getMessage());
                result.addWarning("Failed to process inference: " + tripleKey);
            }
        }

        totalBinaryQueries.addAndGet(binaryQueries);
        totalMultiChoiceQueries.addAndGet(multiChoiceQueries);
        totalQueriesGenerated.addAndGet(binaryQueries + multiChoiceQueries);

        LOGGER.debug("Wrote {} binary queries and {} MC queries for ontology {}",
                binaryQueries, multiChoiceQueries, ontologyName);

        outputService.flush();
    }

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

    private boolean shouldGenerateMultiChoiceQuery(String subject, String predicate,
                                                   Map<String, Map<String, Set<String>>> subjectPredicateObjects) {
        Map<String, Set<String>> predicateObjects = subjectPredicateObjects.get(subject);
        if (predicateObjects == null) return false;

        Set<String> objects = predicateObjects.get(predicate);
        return objects != null && objects.size() > 1;
    }

    private String extractRootEntityFromFilename(File ontologyFile) {
        try {
            String fileName = ontologyFile.getName();
            String rootEntity = fileName.replaceAll("\\.(ttl|owl|rdf|n3)$", "");
            LOGGER.debug("Extracted root entity '{}' from filename '{}'", rootEntity, fileName);
            return rootEntity;
        } catch (Exception e) {
            LOGGER.warn("Could not extract root entity from filename {}: {}",
                    ontologyFile.getName(), e.getMessage());

            String fileName = ontologyFile.getName();
            int lastDot = fileName.lastIndexOf('.');
            return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
        }
    }

    /**
     * Picks a wrong object for a non-entailed (negative) triple.
     * Tries up to 10 random candidates from the same pool (classes for rdf:type, individuals for properties).
     * Returns null if no valid negative can be found.
     */
    private String pickNegativeObject(String subject, String predicate, String trueObject,
                                      Set<String> allEntailedTripleKeys,
                                      List<String> allIndividuals, List<String> allClasses,
                                      Random rng) {
        List<String> pool = "rdf:type".equals(predicate) ? allClasses : allIndividuals;
        if (pool.size() < 2) return null;

        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = pool.get(rng.nextInt(pool.size()));
            if (candidate.equals(trueObject)) continue;
            // Make sure this (subject, predicate, candidate) is NOT an entailed fact
            String negKey = OntologyUtils.createTripleKey(subject, predicate, candidate);
            if (!allEntailedTripleKeys.contains(negKey)) {
                return candidate;
            }
        }
        return null;
    }

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

        if (minTagLength == Integer.MAX_VALUE) {
            minTagLength = 0;
        }

        return new int[]{minTagLength, maxTagLength};
    }

    private void cleanupResources(ComprehensiveExplanationService explanationService) {
        try {
            if (reasoningService != null) {
                reasoningService.close();
            }
        } catch (Exception e) {
            LOGGER.warn("Error closing reasoning service", e);
        }

        System.gc();
    }

    private void logMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        LOGGER.info("Memory Usage: {} MB used, {} MB free, {} MB total, {} MB max",
                String.format("%.2f", usedMemory / (1024.0 * 1024.0)),
                String.format("%.2f", freeMemory / (1024.0 * 1024.0)),
                String.format("%.2f", totalMemory / (1024.0 * 1024.0)),
                String.format("%.2f", maxMemory / (1024.0 * 1024.0)));
    }

    private void finalizeResults(ProcessingResult result) {
        result.setProcessingTimeMs(performanceTracker.getDuration("total_processing"));
        result.setTotalInferences(totalInferencesProcessed.get());
        result.addProcessedQueries(totalQueriesGenerated.get());
        result.setBinaryQueries(totalBinaryQueries.get());
        result.setMultiChoiceQueries(totalMultiChoiceQueries.get());

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        result.setMemoryUsedMB(usedMemory / (1024.0 * 1024.0));

        result.setSuccess(true);

        LOGGER.info("Sequential processing completed successfully!");
        LOGGER.info("Final Statistics:");
        LOGGER.info("Processed ontologies: {}", totalOntologiesProcessed.get());
        LOGGER.info("Total inferences: {}", totalInferencesProcessed.get());
        LOGGER.info("Total queries generated: {}", totalQueriesGenerated.get());
        LOGGER.info("Binary queries: {}", totalBinaryQueries.get());
        LOGGER.info("Multi-choice queries: {}", totalMultiChoiceQueries.get());
    }

    @Override
    public void close() throws Exception {
        LOGGER.info("Closing SmallOntologiesProcessor...");

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