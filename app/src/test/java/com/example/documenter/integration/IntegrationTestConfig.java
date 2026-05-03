package com.example.documenter.integration;

import java.util.List;

import static org.mockito.Mockito.mock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.crawler.ExtensionFilter;
import com.example.documenter.crawler.RepositoryCrawler;
import com.example.documenter.crawler.config.CrawlerProperties;
import com.example.documenter.documentation.DocumentationGenerator;
import com.example.documenter.documentation.DocumentationRunEndpoint;
import com.example.documenter.documentation.DocumentationStorage;
import com.example.documenter.prreviewer.PRCommentPoller;
import com.example.documenter.prreviewer.PRReviewer;
import com.example.documenter.prreviewer.WorkflowTracker;
import com.example.documenter.vcsgateway.VCSProvider;

import io.temporal.client.WorkflowClient;

/**
 * Test configuration that provides mock beans for external dependencies
 * (VCSProvider, AIGateway, Temporal WorkflowClient) and wires the application
 * components for integration testing without requiring real infrastructure.
 */
@TestConfiguration
public class IntegrationTestConfig {

    @Bean
    public VCSProvider vcsProvider() {
        return mock(VCSProvider.class);
    }

    @Bean
    public AIGateway aiGateway() {
        return mock(AIGateway.class);
    }

    @Bean
    public WorkflowClient workflowClient() {
        return mock(WorkflowClient.class);
    }

    @Bean
    public CrawlerProperties crawlerProperties() {
        CrawlerProperties props = new CrawlerProperties();
        props.setIncludeExtensions(List.of(".java", ".ts"));
        props.setExcludeExtensions(List.of(".md"));
        return props;
    }

    @Bean
    public ExtensionFilter extensionFilter(CrawlerProperties crawlerProperties) {
        return new ExtensionFilter(crawlerProperties);
    }

    @Bean
    public RepositoryCrawler repositoryCrawler(VCSProvider vcsProvider, ExtensionFilter extensionFilter) {
        return new RepositoryCrawler(vcsProvider, extensionFilter);
    }

    @Bean
    public DocumentationGenerator documentationGenerator(AIGateway aiGateway) {
        return new DocumentationGenerator(aiGateway);
    }

    @Bean
    public DocumentationStorage documentationStorage(VCSProvider vcsProvider) {
        return new DocumentationStorage(vcsProvider);
    }

    @Bean
    public DocumentationRunEndpoint documentationRunEndpoint(
            RepositoryCrawler crawler,
            DocumentationGenerator generator,
            DocumentationStorage storage) {
        return new DocumentationRunEndpoint(crawler, generator, storage,
                "test-owner", "test-repo", "main");
    }

    @Bean
    public WorkflowTracker workflowTracker() {
        return new WorkflowTracker();
    }

    @Bean
    public PRReviewer prReviewer(VCSProvider vcsProvider,
                                  WorkflowTracker workflowTracker,
                                  WorkflowClient workflowClient) {
        return new PRReviewer(vcsProvider, workflowTracker, workflowClient,
                "test-owner", "test-repo", "main");
    }

    @Bean
    public PRCommentPoller prCommentPoller(VCSProvider vcsProvider,
                                            WorkflowTracker workflowTracker,
                                            WorkflowClient workflowClient) {
        return new PRCommentPoller(vcsProvider, workflowTracker, workflowClient, "test-bot");
    }
}
