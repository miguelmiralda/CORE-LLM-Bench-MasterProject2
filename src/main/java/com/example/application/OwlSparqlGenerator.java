// com/example/application/OwlSparqlGenerator.java
package com.example.application;
import com.example.evaluation.LlmEvaluationService;
import com.example.evaluation.AccuracyReportService;
import com.example.config.ProcessingConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.example.processing.SmallOntologiesProcessor;
import com.example.processing.ProcessingResult;
import com.example.ontology.DefaultOntologyService;
import com.example.reasoning.PelletReasoningService;
import com.example.query.SparqlQueryGenerationService;
import com.example.output.StreamingOutputService;
 
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;

/**
 * Professional OWL Inference Processor
 * Processes multiple small ontologies to generate comprehensive explanations and queries
 */
@SpringBootApplication(scanBasePackages = "com.example")
@EnableConfigurationProperties(ProcessingConfiguration.class)
public class OwlSparqlGenerator implements CommandLineRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(OwlSparqlGenerator.class);

    @Autowired
    @Qualifier("processingConfiguration")
    private ProcessingConfiguration config;

    @Autowired
    private LlmEvaluationService llmEvaluationService;

    @Autowired
    private AccuracyReportService accuracyReportService;

    private SmallOntologiesProcessor processor;

    public static void main(String[] args) {
        configureJVM();
        SpringApplication.run(OwlSparqlGenerator.class, args);
    }

    private static void configureJVM() {
        // Enhanced JVM configuration for large ontology processing
        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "1");
        System.setProperty("java.awt.headless", "true");

        // Reduce logging overhead for better performance
        System.setProperty("logging.level.openllet", "WARN");
        System.setProperty("logging.level.org.semanticweb.owlapi", "WARN");

        // Memory optimization settings
        System.setProperty("java.util.concurrent.ThreadPoolExecutor.allowCoreThreadTimeOut", "true");

        LOGGER.info("JVM configured for sequential large ontology processing");
    }

    @Override
    public void run(String... args) throws Exception {
        // Add memory monitoring
        logInitialMemoryStatus();

        // Override config from command line args if provided
        if (args.length > 0) {
            config.setOntologiesDirectory(args[0]);
        }
        if (args.length > 1) {
            config.setOutputDirectory(args[1]);
        }

        LOGGER.info("=== Professional OWL Inference Processor (Sequential Mode) ===");
        logSystemInfo();

        // Initialize services
        DefaultOntologyService ontologyService = new DefaultOntologyService();
        PelletReasoningService reasoningService = new PelletReasoningService();
        SparqlQueryGenerationService queryService = new SparqlQueryGenerationService();
        StreamingOutputService outputService = new StreamingOutputService(config.getOutputDirectory());

        processor = new SmallOntologiesProcessor(
        ontologyService,
        reasoningService,
        queryService,
        outputService,
        config,
        llmEvaluationService,
        accuracyReportService
);

        try {
            LOGGER.info("Starting SEQUENTIAL processing of ontologies...");
            ProcessingResult result = processor.processSmallOntologies(config.getOntologiesDirectory());
            logResults(result);

        } catch (Exception e) {
            LOGGER.error("Processing failed", e);
            throw e;
        }
    }

    private void logInitialMemoryStatus() {
        Runtime runtime = Runtime.getRuntime();
        LOGGER.info("Initial Memory Status:");
        LOGGER.info("  Max memory: {:.2f} GB", runtime.maxMemory() / (1024.0 * 1024.0 * 1024.0));
        LOGGER.info("  Total memory: {:.2f} GB", runtime.totalMemory() / (1024.0 * 1024.0 * 1024.0));
        LOGGER.info("  Free memory: {:.2f} GB", runtime.freeMemory() / (1024.0 * 1024.0 * 1024.0));
    }

    private void logSystemInfo() {
        Runtime runtime = Runtime.getRuntime();
        LOGGER.info("System Configuration:");
        LOGGER.info("  Available processors: {}", runtime.availableProcessors());
        LOGGER.info("  Max memory: {:.2f} GB", runtime.maxMemory() / (1024.0 * 1024.0 * 1024.0));
        LOGGER.info("  Ontologies directory: {}", config.getOntologiesDirectory());
        LOGGER.info("  Output directory: {}", config.getOutputDirectory());
        LOGGER.info("  Max explanations per inference: {}", config.getMaxExplanationsPerInference());
        LOGGER.info("  Thread pool size: {}", config.getThreadPoolSize());
        LOGGER.info("  Processing timeout: {} hours", config.getTimeoutHours());
    }

    private void logResults(ProcessingResult result) {
        LOGGER.info("=== PROCESSING COMPLETED ===");
        LOGGER.info("Results Summary:");
        LOGGER.info("  Total inferences processed: {}", result.getTotalInferences());
        LOGGER.info("  Total explanation paths: {}", result.getProcessedExplanations());
        LOGGER.info("  Generated queries: {}", result.getProcessedQueries());
        LOGGER.info("  Binary queries: {}", result.getBinaryQueries());
        LOGGER.info("  Multi-choice queries: {}", result.getMultiChoiceQueries());
        LOGGER.info("  Processing time: {} minutes", String.format("%.2f", result.getProcessingTimeMs() / 60000.0));
        LOGGER.info("  Memory used: {} MB", String.format("%.2f", result.getMemoryUsedMB()));
        LOGGER.info("  Success: {}", result.isSuccess());

        if (result.hasErrors()) {
            LOGGER.warn("Errors encountered ({}): ", result.getErrorCount());
            result.getErrors().forEach(error -> LOGGER.warn("  - {}", error));
        }

        if (result.hasWarnings()) {
            LOGGER.info("Warnings ({}): ", result.getWarningCount());
            result.getWarnings().forEach(warning -> LOGGER.debug("  - {}", warning));
        }

        // Performance insights
        if (result.getTotalInferences() > 0) {
            double avgTimePerInference = (double) result.getProcessingTimeMs() / result.getTotalInferences();
            LOGGER.info("  Average time per inference: {} ms", String.format("%.2f", avgTimePerInference));
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (processor != null) {
                LOGGER.info("Shutting down processor...");
                processor.close();
                LOGGER.info("Processor shut down successfully");
            }
        } catch (Exception e) {
            LOGGER.warn("Error during cleanup: {}", e.getMessage());
        } finally {
            // Suggest garbage collection
            System.gc();
        }
    }
}