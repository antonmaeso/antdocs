package com.example.documenter.vcsgateway.github;

import com.example.documenter.vcsgateway.VcsApiException;
import com.example.documenter.vcsgateway.config.VcsProperties;
import com.example.documenter.vcsgateway.domain.TreeEntry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitHubProvider} rate-limit handling and auth/version headers.
 */
class GitHubProviderRateLimitTest {

    private MockWebServer mockWebServer;
    private GitHubProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        VcsProperties props = new VcsProperties();
        props.setProvider("github");
        props.setBotUsername("test-bot");

        VcsProperties.GitHubConfig githubConfig = new VcsProperties.GitHubConfig();
        githubConfig.setToken("test-token");
        githubConfig.setApiBaseUrl(mockWebServer.url("/").toString());
        props.setGithub(githubConfig);

        provider = new GitHubProvider(props);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ---- Rate-limit retry ----

    @Test
    void rateLimitResponse_retriesAfterSleep() throws Exception {
        long resetEpoch = Instant.now().getEpochSecond() + 1;

        // First request: 429 with X-RateLimit-Reset header
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("X-RateLimit-Reset", String.valueOf(resetEpoch))
                .addHeader("Content-Type", "application/json"));

        // Second request: 200 success
        String successJson = """
                {
                  "sha": "abc123",
                  "tree": [
                    {"path": "README.md", "type": "blob", "sha": "aaa111"}
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(successJson)
                .addHeader("Content-Type", "application/json"));

        List<TreeEntry> entries = provider.fetchFileTree("owner", "repo", "main");

        // Verify the retry succeeded and returned data
        assertEquals(1, entries.size());
        assertEquals("README.md", entries.get(0).path());

        // Verify exactly 2 requests were made (original + retry)
        assertEquals(2, mockWebServer.getRequestCount());

        // Verify both requests hit the same endpoint
        RecordedRequest firstRequest = mockWebServer.takeRequest();
        RecordedRequest secondRequest = mockWebServer.takeRequest();
        assertTrue(firstRequest.getPath().contains("/repos/owner/repo/git/trees/main"));
        assertTrue(secondRequest.getPath().contains("/repos/owner/repo/git/trees/main"));
    }

    // ---- Double rate-limit throws VcsApiException ----

    @Test
    void rateLimitResponse_doubleRateLimit_throwsVcsApiException() {
        long resetEpoch = Instant.now().getEpochSecond() + 1;

        // First request: 429
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("X-RateLimit-Reset", String.valueOf(resetEpoch))
                .addHeader("Content-Type", "application/json"));

        // Second request (retry): also 429
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("X-RateLimit-Reset", String.valueOf(resetEpoch + 60))
                .addHeader("Content-Type", "application/json"));

        VcsApiException exception = assertThrows(VcsApiException.class,
                () -> provider.fetchFileTree("owner", "repo", "main"));

        assertEquals(429, exception.getHttpStatus());
    }

    // ---- Authorization header ----

    @Test
    void allRequests_carryAuthorizationHeader() throws Exception {
        String json = """
                {
                  "sha": "abc123",
                  "tree": []
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        provider.fetchFileTree("owner", "repo", "main");

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("Bearer test-token", request.getHeader("Authorization"));
    }

    // ---- GitHub API version header ----

    @Test
    void allRequests_carryGitHubApiVersionHeader() throws Exception {
        String json = """
                {
                  "sha": "abc123",
                  "tree": []
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        provider.fetchFileTree("owner", "repo", "main");

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"));
    }
}
