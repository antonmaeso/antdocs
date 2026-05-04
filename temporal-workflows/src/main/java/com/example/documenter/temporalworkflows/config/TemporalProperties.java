package com.example.documenter.temporalworkflows.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for the Temporal server connection.
 * Bound from the {@code temporal.*} namespace in application.yml.
 */
@ConfigurationProperties(prefix = "temporal")
@Validated
public class TemporalProperties {

    @NotBlank
    private String host;

    @Min(1)
    private int port = 7233;

    @NotBlank
    private String namespace = "default";

    @Valid
    private PrReviewWorkflowConfig prReviewWorkflow = new PrReviewWorkflowConfig();

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public PrReviewWorkflowConfig getPrReviewWorkflow() {
        return prReviewWorkflow;
    }

    public void setPrReviewWorkflow(PrReviewWorkflowConfig prReviewWorkflow) {
        this.prReviewWorkflow = prReviewWorkflow;
    }

    /**
     * Configuration specific to the PR review workflow behaviour.
     */
    public static class PrReviewWorkflowConfig {

        @Min(1)
        private long signalTimeoutSeconds = 86400L;

        public long getSignalTimeoutSeconds() {
            return signalTimeoutSeconds;
        }

        public void setSignalTimeoutSeconds(long signalTimeoutSeconds) {
            this.signalTimeoutSeconds = signalTimeoutSeconds;
        }
    }
}
