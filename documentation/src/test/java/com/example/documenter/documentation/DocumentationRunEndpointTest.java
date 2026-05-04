package com.example.documenter.documentation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.crawler.RepositoryCrawler;
import com.example.documenter.vcsgateway.VCSProvider;

/**
 * Unit tests for {@link DocumentationRunEndpoint}.
 *
 * <p>Tests the REST endpoint logic: HTTP status codes, config validation,
 * concurrency guard, and status tracking.
 */
@ExtendWith(MockitoExtension.class)
class DocumentationRunEndpointTest {

    private RepositoryCrawler createMockCrawler(VCSProvider vcsProvider) {
        // RepositoryCrawler is a concrete class; we construct it with a mock VCSProvider
        // and a filter that accepts everything
        var props = new com.example.documenter.crawler.config.CrawlerProperties();
        props.setIncludeExtensions(List.of());
        props.setExcludeExtensions(List.of());
        var filter = new com.example.documenter.crawler.ExtensionFilter(props);
        return new RepositoryCrawler(vcsProvider, filter);
    }

    @Test
    void startRun_returns202WithRunId() {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);
        RepositoryCrawler crawler = createMockCrawler(vcsProvider);
        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        DocumentationStorage storage = new DocumentationStorage(vcsProvider);

        DocumentationRunEndpoint endpoint = new DocumentationRunEndpoint(
                crawler, generator, storage, "owner", "repo", "main");

        ResponseEntity<Map<String, String>> response = endpoint.startRun();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("runId"));
    }

    @Test
    void startRun_returns400WhenOwnerMissing() {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);
        RepositoryCrawler crawler = createMockCrawler(vcsProvider);
        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        DocumentationStorage storage = new DocumentationStorage(vcsProvider);

        DocumentationRunEndpoint endpoint = new DocumentationRunEndpoint(
                crawler, generator, storage, "", "repo", "main");

        ResponseEntity<Map<String, String>> response = endpoint.startRun();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void startRun_returns400WhenRepoNull() {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);
        RepositoryCrawler crawler = createMockCrawler(vcsProvider);
        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        DocumentationStorage storage = new DocumentationStorage(vcsProvider);

        DocumentationRunEndpoint endpoint = new DocumentationRunEndpoint(
                crawler, generator, storage, "owner", null, "main");

        ResponseEntity<Map<String, String>> response = endpoint.startRun();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void startRun_returns409WhenRunInProgress() throws InterruptedException {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);

        // Make the crawl take a long time so the run stays IN_PROGRESS
        when(vcsProvider.fetchFileTree("owner", "repo", "main"))
                .thenAnswer(invocation -> {
                    Thread.sleep(5000);
                    return List.of();
                });

        RepositoryCrawler crawler = createMockCrawler(vcsProvider);
        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        DocumentationStorage storage = new DocumentationStorage(vcsProvider);

        DocumentationRunEndpoint endpoint = new DocumentationRunEndpoint(
                crawler, generator, storage, "owner", "repo", "main");

        // Start first run
        ResponseEntity<Map<String, String>> first = endpoint.startRun();
        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());

        // Small delay to let the async task start
        Thread.sleep(200);

        // Second run should be rejected
        ResponseEntity<Map<String, String>> second = endpoint.startRun();
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
    }

    @Test
    void getRunStatus_returns404ForUnknownRunId() {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);
        RepositoryCrawler crawler = createMockCrawler(vcsProvider);
        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        DocumentationStorage storage = new DocumentationStorage(vcsProvider);

        DocumentationRunEndpoint endpoint = new DocumentationRunEndpoint(
                crawler, generator, storage, "owner", "repo", "main");

        ResponseEntity<Map<String, String>> response = endpoint.getRunStatus("unknown-id");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getRunStatus_returnsInProgressForActiveRun() throws InterruptedException {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);

        when(vcsProvider.fetchFileTree("owner", "repo", "main"))
                .thenAnswer(invocation -> {
                    Thread.sleep(5000);
                    return List.of();
                });

        RepositoryCrawler crawler = createMockCrawler(vcsProvider);
        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        DocumentationStorage storage = new DocumentationStorage(vcsProvider);

        DocumentationRunEndpoint endpoint = new DocumentationRunEndpoint(
                crawler, generator, storage, "owner", "repo", "main");

        ResponseEntity<Map<String, String>> startResponse = endpoint.startRun();
        String runId = startResponse.getBody().get("runId");

        // Give async task a moment to start
        Thread.sleep(100);

        ResponseEntity<Map<String, String>> statusResponse = endpoint.getRunStatus(runId);

        assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
        assertEquals("IN_PROGRESS", statusResponse.getBody().get("status"));
    }

    @Test
    void getRunStatus_returnsCompletedAfterSuccessfulRun() throws InterruptedException {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);

        // Crawl returns one file
        when(vcsProvider.fetchFileTree("owner", "repo", "main"))
                .thenReturn(List.of(
                        new com.example.documenter.vcsgateway.domain.TreeEntry("src/A.java", "blob", "sha1")));
        when(vcsProvider.fetchFileContent("owner", "repo", "src/A.java", "main"))
                .thenReturn("public class A {}");

        // AI generates docs
        when(aiGateway.generateDocumentation(anyString(), anyString()))
                .thenReturn("# A\nDocumentation for A.");
        when(aiGateway.generateRepositoryReadme(anyList(), anyString()))
                .thenReturn("# repo\nOverview");

        // Storage: companion repo exists
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main"))
                .thenReturn(List.of());

        RepositoryCrawler crawler = createMockCrawler(vcsProvider);
        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        DocumentationStorage storage = new DocumentationStorage(vcsProvider);

        DocumentationRunEndpoint endpoint = new DocumentationRunEndpoint(
                crawler, generator, storage, "owner", "repo", "main");

        ResponseEntity<Map<String, String>> startResponse = endpoint.startRun();
        String runId = startResponse.getBody().get("runId");

        // Wait for async task to complete
        Thread.sleep(2000);

        ResponseEntity<Map<String, String>> statusResponse = endpoint.getRunStatus(runId);

        assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
        assertEquals("COMPLETED", statusResponse.getBody().get("status"));
    }

    @Test
    void getRunStatus_returnsFailedOnException() throws InterruptedException {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);

        when(vcsProvider.fetchFileTree("owner", "repo", "main"))
                .thenThrow(new RuntimeException("VCS error"));

        RepositoryCrawler crawler = createMockCrawler(vcsProvider);
        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        DocumentationStorage storage = new DocumentationStorage(vcsProvider);

        DocumentationRunEndpoint endpoint = new DocumentationRunEndpoint(
                crawler, generator, storage, "owner", "repo", "main");

        ResponseEntity<Map<String, String>> startResponse = endpoint.startRun();
        String runId = startResponse.getBody().get("runId");

        // Wait for async task to complete
        Thread.sleep(1000);

        ResponseEntity<Map<String, String>> statusResponse = endpoint.getRunStatus(runId);

        assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
        assertEquals("FAILED", statusResponse.getBody().get("status"));
    }
}
