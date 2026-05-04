package com.example.documenter.vcsgateway.github;

import com.example.documenter.vcsgateway.config.VcsProperties;
import com.example.documenter.vcsgateway.domain.PullRequestComment;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitHubProvider} write operations using MockWebServer.
 */
class GitHubProviderWriteTest {

    private MockWebServer mockWebServer;
    private GitHubProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    // ---- postPullRequestComment ----

    @Test
    void postPullRequestComment_sendsCorrectRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        PullRequestComment comment = new PullRequestComment("Great work!", null, 0, "reviewer", 0);
        provider.postPullRequestComment("owner", "repo", 42, comment);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/repos/owner/repo/issues/42/comments", request.getPath());

        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), Map.class);
        assertEquals("Great work!", body.get("body"));
    }

    // ---- approvePullRequest ----

    @Test
    void approvePullRequest_sendsCorrectRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json"));

        provider.approvePullRequest("owner", "repo", 42);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/repos/owner/repo/pulls/42/reviews", request.getPath());

        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), Map.class);
        assertEquals("APPROVE", body.get("event"));
    }

    // ---- createRepository ----

    @Test
    void createRepository_orgEndpoint() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        provider.createRepository("owner", "new-repo", true);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/orgs/owner/repos", request.getPath());

        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), Map.class);
        assertEquals("new-repo", body.get("name"));
        assertEquals(true, body.get("private"));
    }

    @Test
    void createRepository_fallsBackToUserEndpoint() throws Exception {
        // First request to org endpoint returns 404
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .addHeader("Content-Type", "application/json"));
        // Second request to user endpoint returns 201
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        provider.createRepository("owner", "new-repo", false);

        // First request: org endpoint
        RecordedRequest orgRequest = mockWebServer.takeRequest();
        assertEquals("POST", orgRequest.getMethod());
        assertEquals("/orgs/owner/repos", orgRequest.getPath());

        // Second request: user endpoint fallback
        RecordedRequest userRequest = mockWebServer.takeRequest();
        assertEquals("POST", userRequest.getMethod());
        assertEquals("/user/repos", userRequest.getPath());

        Map<String, Object> body = objectMapper.readValue(userRequest.getBody().readUtf8(), Map.class);
        assertEquals("new-repo", body.get("name"));
        assertEquals(false, body.get("private"));
    }

    @Test
    void createRepository_silentlySucceedsOn422() {
        // 422 means repo already exists — should not throw
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(422)
                .addHeader("Content-Type", "application/json"));

        assertDoesNotThrow(() -> provider.createRepository("owner", "existing-repo", true));
    }

    // ---- pushFile ----

    @Test
    void pushFile_newFile_noShaFetch() throws Exception {
        // GET for existing file returns 404 (file doesn't exist)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .addHeader("Content-Type", "application/json"));
        // PUT to create the file returns 201
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        provider.pushFile("owner", "repo", "README.md", "Hello World", "Add README");

        // First request: GET to check if file exists
        RecordedRequest getRequest = mockWebServer.takeRequest();
        assertEquals("GET", getRequest.getMethod());
        assertTrue(getRequest.getPath().startsWith("/repos/owner/repo/contents/"),
                "GET path should target the contents endpoint: " + getRequest.getPath());

        // Second request: PUT to create the file
        RecordedRequest putRequest = mockWebServer.takeRequest();
        assertEquals("PUT", putRequest.getMethod());
        assertTrue(putRequest.getPath().startsWith("/repos/owner/repo/contents/"),
                "PUT path should target the contents endpoint: " + putRequest.getPath());

        Map<String, Object> body = objectMapper.readValue(putRequest.getBody().readUtf8(), Map.class);
        assertEquals("Add README", body.get("message"));
        assertNotNull(body.get("content"));
        assertFalse(body.containsKey("sha"), "New file should not include sha field");
    }

    @Test
    void pushFile_existingFile_fetchesShaFirst() throws Exception {
        // GET for existing file returns 200 with sha
        String getResponse = """
                {
                  "sha": "existing-sha",
                  "content": "b2xkIGNvbnRlbnQ="
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(getResponse)
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json"));
        // PUT to update the file returns 200
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json"));

        provider.pushFile("owner", "repo", "README.md", "Updated content", "Update README");

        // First request: GET to fetch existing SHA
        RecordedRequest getRequest = mockWebServer.takeRequest();
        assertEquals("GET", getRequest.getMethod());
        assertTrue(getRequest.getPath().startsWith("/repos/owner/repo/contents/"),
                "GET path should target the contents endpoint: " + getRequest.getPath());

        // Second request: PUT with sha included
        RecordedRequest putRequest = mockWebServer.takeRequest();
        assertEquals("PUT", putRequest.getMethod());

        Map<String, Object> body = objectMapper.readValue(putRequest.getBody().readUtf8(), Map.class);
        assertEquals("Update README", body.get("message"));
        assertEquals("existing-sha", body.get("sha"));
        assertNotNull(body.get("content"));
    }

    @Test
    void pushFile_contentIsBase64Encoded() throws Exception {
        String originalContent = "public class Main { }";

        // GET returns 404 (new file)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .addHeader("Content-Type", "application/json"));
        // PUT returns 201
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        provider.pushFile("owner", "repo", "src/Main.java", originalContent, "Add Main.java");

        // Skip the GET request
        mockWebServer.takeRequest();

        // Inspect the PUT request
        RecordedRequest putRequest = mockWebServer.takeRequest();
        Map<String, Object> body = objectMapper.readValue(putRequest.getBody().readUtf8(), Map.class);

        String encodedContent = (String) body.get("content");
        assertNotNull(encodedContent, "content field should be present");

        // Verify it's valid base64 that decodes to the original content
        byte[] decoded = Base64.getDecoder().decode(encodedContent);
        assertEquals(originalContent, new String(decoded));
    }
}
