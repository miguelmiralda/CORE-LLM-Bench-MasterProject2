// com/example/config/ProcessingConfiguration.java
package com.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration properties for ontology processing
 */
@Configuration
@ConfigurationProperties(prefix = "processing")
@Primary
public class ProcessingConfiguration {

    private String ontologiesDirectory = "src/main/resources/small_ontologies";
    private String outputDirectory = "./output";
    private int timeoutHours = 2;
    private int maxExplanationsPerInference = 20;
    private int threadPoolSize = 4;
    private int batchSize = 50;
    private boolean enableDetailedLogging = false;

    // Getters and setters
    public String getOntologiesDirectory() { return ontologiesDirectory; }
    public void setOntologiesDirectory(String ontologiesDirectory) { this.ontologiesDirectory = ontologiesDirectory; }

    public String getOutputDirectory() { return outputDirectory; }
    public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }

    public int getTimeoutHours() { return timeoutHours; }
    public void setTimeoutHours(int timeoutHours) { this.timeoutHours = timeoutHours; }

    public int getMaxExplanationsPerInference() { return maxExplanationsPerInference; }
    public void setMaxExplanationsPerInference(int maxExplanationsPerInference) {
        this.maxExplanationsPerInference = maxExplanationsPerInference;
    }

    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public boolean isEnableDetailedLogging() { return enableDetailedLogging; }
    public void setEnableDetailedLogging(boolean enableDetailedLogging) {
        this.enableDetailedLogging = enableDetailedLogging;
    }

    @Override
    public String toString() {
        return "ProcessingConfiguration{" +
                "ontologiesDirectory='" + ontologiesDirectory + '\'' +
                ", outputDirectory='" + outputDirectory + '\'' +
                ", timeoutHours=" + timeoutHours +
                ", maxExplanationsPerInference=" + maxExplanationsPerInference +
                ", threadPoolSize=" + threadPoolSize +
                ", batchSize=" + batchSize +
                '}';
    }
}