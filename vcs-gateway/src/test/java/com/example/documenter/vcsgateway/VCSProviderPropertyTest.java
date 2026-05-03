package com.example.documenter.vcsgateway;

import com.example.documenter.vcsgateway.config.VcsProperties;
import com.example.documenter.vcsgateway.domain.TreeEntry;
import com.example.documenter.vcsgateway.github.GitHubProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for VCS provider implementations.
 * Uses GitHubProvider as the representative provider — unit tests cover all three.
 */
class VCSProviderPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer mockWebServer;

    @BeforeProperty
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterProperty
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // Feature: github-repo-documenter, Property 18: VCS provider API responses are correctly mapped to domain objects
    // **Validates: Requirements 8.2, 8.3, 8.4**
    @Property(tries = 25)
    void apiResponsesMappedCorrectlyToDomainObjects(
            @ForAll("filePaths") String path,
            @ForAll("treeEntryTypes") String type,
            @ForAll("hexSha") String sha
    ) throws Exception {
        // Build a JSON response with the generated values using Jackson to guarantee valid JSON
        Map<String, Object> treeEntry = Map.of("path", path, "type", type, "sha", sha);
        Map<String, Object> responseBody = Map.of("sha", "rootsha", "tree", List.of(treeEntry));
        String json = MAPPER.writeValueAsString(responseBody);

        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        VcsProperties props = createProps("test-token");
        GitHubProvider provider = new GitHubProvider(props);

        // Call fetchFileTree()
        List<TreeEntry> entries = provider.fetchFileTree("owner", "repo", "main");

        // Assert the returned TreeEntry matches exactly
        assertEquals(1, entries.size(), "Should return exactly one tree entry");
        TreeEntry entry = entries.get(0);
        assertEquals(path, entry.path(), "path must match the JSON value");
        assertEquals(type, entry.type(), "type must match the JSON value");
        assertEquals(sha, entry.sha(), "sha must match the JSON value");
    }

    @Provide
    Arbitrary<String> treeEntryTypes() {
        return Arbitraries.of("blob", "tree", "commit");
    }

    @Provide
    Arbitrary<String> filePaths() {
        // Generate paths using printable ASCII characters safe for JSON (no control chars)
        return Arbitraries.strings()
                .withCharRange('!', '~')  // printable ASCII excluding space
                .ofMinLength(1)
                .ofMaxLength(50);
    }

    @Provide
    Arbitrary<String> hexSha() {
        // Generate a 40-character lowercase hex string (valid git SHA)
        return Arbitraries.strings()
                .withChars('0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                           'a', 'b', 'c', 'd', 'e', 'f')
                .ofLength(40);
    }

    // Feature: github-repo-documenter, Property 19: All VCS API requests carry the configured PAT
    // **Validates: Requirement 8.11**
    @Property(tries = 25)
    void allRequestsCarryConfiguredPAT(
            @ForAll @StringLength(min = 10, max = 50) @AlphaChars String token
    ) throws Exception {
        // Create VcsProperties with the generated token
        VcsProperties props = createProps(token);
        GitHubProvider provider = new GitHubProvider(props);

        // Enqueue a valid response so the call succeeds
        String json = """
                {
                  "sha": "abc123",
                  "tree": []
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        // Call any method (fetchFileTree)
        provider.fetchFileTree("owner", "repo", "main");

        // Verify the Authorization header is "Bearer {token}"
        RecordedRequest request = mockWebServer.takeRequest();
        String authHeader = request.getHeader("Authorization");
        assertNotNull(authHeader, "Authorization header must be present");
        assertEquals("Bearer " + token, authHeader,
                "Authorization header must be 'Bearer {token}'");
    }

    private VcsProperties createProps(String token) {
        VcsProperties props = new VcsProperties();
        props.setProvider("github");
        props.setBotUsername("test-bot");

        VcsProperties.GitHubConfig githubConfig = new VcsProperties.GitHubConfig();
        githubConfig.setToken(token);
        githubConfig.setApiBaseUrl(mockWebServer.url("/").toString());
        props.setGithub(githubConfig);

        return props;
    }
}
