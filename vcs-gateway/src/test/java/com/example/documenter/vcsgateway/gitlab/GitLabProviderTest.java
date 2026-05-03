package com.example.documenter.vcsgateway.gitlab;

import com.example.documenter.vcsgateway.config.VcsProperties;
import com.example.documenter.vcsgateway.domain.FileDiff;
import com.example.documenter.vcsgateway.domain.PullRequest;
import com.example.documenter.vcsgateway.domain.PullRequestComment;
import com.example.documenter.vcsgateway.domain.PullRequestDiff;
import com.example.documenter.vcsgateway.domain.TreeEntry;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitLabProvider} — read operations, write operations,
 * rate-limit handling, and auth header verification.
 */
class GitLabProviderTest {

    private MockWebServer mockWebServer;
    private GitLabProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        VcsProperties props = new VcsProperties();
        props.setProvider("gitlab");
        props.setBotUsername("test-bot");

        VcsProperties.GitLabConfig gitlabConfig = new VcsProperties.GitLabConfig();
        gitlabConfig.setToken("test-token");
        gitlabConfig.setApiBaseUrl(mockWebServer.url("/").toString());
        props.setGitlab(gitlabConfig);

        provider = new GitLabProvider(props);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ========== Read operations ==========

    @Test
    void fetchFileTree_mapsEntries() {
        // First page with entries
        String page1 = """
                [
                  {"path": "src/Main.java", "type": "blob", "id": "aaa111"},
                  {"path": "src", "type": "tree", "id": "bbb222"}
                ]
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(page1)
                .addHeader("Content-Type", "application/json"));

        // Second page empty — signals end of pagination
        mockWebServer.enqueue(new MockResponse()
                .setBody("[]")
                .addHeader("Content-Type", "application/json"));

        List<TreeEntry> entries = provider.fetchFileTree("owner", "repo", "main");

        assertEquals(2, entries.size());

        assertEquals("src/Main.java", entries.get(0).path());
        assertEquals("blob", entries.get(0).type());
        assertEquals("aaa111", entries.get(0).sha());

        assertEquals("src", entries.get(1).path());
        assertEquals("tree", entries.get(1).type());
        assertEquals("bbb222", entries.get(1).sha());
    }

    @Test
    void fetchFileContent_returnsRawContent() {
        String rawContent = "public class Hello { }";
        mockWebServer.enqueue(new MockResponse()
                .setBody(rawContent)
                .addHeader("Content-Type", "text/plain"));

        String content = provider.fetchFileContent("owner", "repo", "src/Hello.java", "main");

        assertEquals(rawContent, content);
    }

    @Test
    void listOpenPullRequests_mapsMergeRequests() {
        String json = """
                [
                  {
                    "iid": 10,
                    "title": "Add feature X",
                    "diff_refs": {
                      "head_sha": "head111",
                      "base_sha": "base222"
                    }
                  },
                  {
                    "iid": 20,
                    "title": "Fix bug Y",
                    "diff_refs": {
                      "head_sha": "head333",
                      "base_sha": "base444"
                    }
                  }
                ]
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        List<PullRequest> prs = provider.listOpenPullRequests("owner", "repo");

        assertEquals(2, prs.size());

        assertEquals(10, prs.get(0).number());
        assertEquals("Add feature X", prs.get(0).title());
        assertEquals("head111", prs.get(0).headSha());
        assertEquals("base222", prs.get(0).baseSha());

        assertEquals(20, prs.get(1).number());
        assertEquals("Fix bug Y", prs.get(1).title());
        assertEquals("head333", prs.get(1).headSha());
        assertEquals("base444", prs.get(1).baseSha());
    }

    @Test
    void fetchPullRequestDiff_mapsDiffs() {
        String json = """
                [
                  {"new_path": "src/Main.java", "diff": "@@ -1,3 +1,4 @@\\n+new line"},
                  {"new_path": "src/Utils.java", "diff": "@@ -1,2 +1,3 @@\\n+utility"}
                ]
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        PullRequestDiff result = provider.fetchPullRequestDiff("owner", "repo", 10);

        assertEquals(10, result.prNumber());
        List<FileDiff> fileDiffs = result.fileDiffs();
        assertEquals(2, fileDiffs.size());

        assertEquals("src/Main.java", fileDiffs.get(0).path());
        assertTrue(fileDiffs.get(0).patch().contains("+new line"));

        assertEquals("src/Utils.java", fileDiffs.get(1).path());
        assertTrue(fileDiffs.get(1).patch().contains("+utility"));
    }

    @Test
    void listPullRequestComments_mapsNotes() {
        String json = """
                [
                  {
                    "id": 1001,
                    "body": "Looks good to me",
                    "author": {"username": "alice"}
                  },
                  {
                    "id": 1002,
                    "body": "Please fix the typo",
                    "author": {"username": "bob"}
                  }
                ]
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        List<PullRequestComment> comments = provider.listPullRequestComments("owner", "repo", 10);

        assertEquals(2, comments.size());

        assertEquals(1001, comments.get(0).id());
        assertEquals("Looks good to me", comments.get(0).body());
        assertEquals("alice", comments.get(0).author());

        assertEquals(1002, comments.get(1).id());
        assertEquals("Please fix the typo", comments.get(1).body());
        assertEquals("bob", comments.get(1).author());
    }

    // ========== Write operations ==========

    @Test
    @SuppressWarnings("unchecked")
    void postPullRequestComment_sendsCorrectRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        PullRequestComment comment = new PullRequestComment("Great work!", null, 0, "reviewer", 0);
        provider.postPullRequestComment("owner", "repo", 10, comment);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        // GitLab uses URL-encoded project ID: owner%2Frepo
        assertTrue(request.getPath().contains("/merge_requests/10/notes"));

        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), Map.class);
        assertEquals("Great work!", body.get("body"));
    }

    @Test
    void approvePullRequest_sendsCorrectRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json"));

        provider.approvePullRequest("owner", "repo", 10);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().contains("/merge_requests/10/approve"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createRepository_sendsCorrectRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        provider.createRepository("my-group", "new-repo", true);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/projects", request.getPath());

        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), Map.class);
        assertEquals("new-repo", body.get("name"));
        assertEquals("my-group", body.get("namespace_id"));
        assertEquals("private", body.get("visibility"));
    }

    @Test
    void createRepository_silentlySucceedsOn409() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(409)
                .addHeader("Content-Type", "application/json"));

        assertDoesNotThrow(() -> provider.createRepository("my-group", "existing-repo", true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pushFile_createsNewFile() throws Exception {
        // GET to check existence returns 404 — file does not exist
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .addHeader("Content-Type", "application/json"));
        // POST to create the file returns 201
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        provider.pushFile("owner", "repo", "README.md", "Hello World", "Add README");

        // First request: GET to check if file exists
        RecordedRequest getRequest = mockWebServer.takeRequest();
        assertEquals("GET", getRequest.getMethod());
        assertTrue(getRequest.getPath().contains("/repository/files/"));

        // Second request: POST to create the file
        RecordedRequest postRequest = mockWebServer.takeRequest();
        assertEquals("POST", postRequest.getMethod());
        assertTrue(postRequest.getPath().contains("/repository/files/"));

        Map<String, Object> body = objectMapper.readValue(postRequest.getBody().readUtf8(), Map.class);
        assertEquals("Hello World", body.get("content"));
        assertEquals("Add README", body.get("commit_message"));
        assertEquals("main", body.get("branch"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pushFile_updatesExistingFile() throws Exception {
        // GET to check existence returns 200 — file exists
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{}")
                .addHeader("Content-Type", "application/json"));
        // PUT to update the file returns 200
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json"));

        provider.pushFile("owner", "repo", "README.md", "Updated content", "Update README");

        // First request: GET to check if file exists
        RecordedRequest getRequest = mockWebServer.takeRequest();
        assertEquals("GET", getRequest.getMethod());

        // Second request: PUT to update the file
        RecordedRequest putRequest = mockWebServer.takeRequest();
        assertEquals("PUT", putRequest.getMethod());
        assertTrue(putRequest.getPath().contains("/repository/files/"));

        Map<String, Object> body = objectMapper.readValue(putRequest.getBody().readUtf8(), Map.class);
        assertEquals("Updated content", body.get("content"));
        assertEquals("Update README", body.get("commit_message"));
        assertEquals("main", body.get("branch"));
    }

    // ========== Rate-limit and auth ==========

    @Test
    void rateLimitResponse_retriesAfterSleep() throws Exception {
        long resetEpoch = Instant.now().getEpochSecond() + 1;

        // First request: 429 with RateLimit-Reset header
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("RateLimit-Reset", String.valueOf(resetEpoch))
                .addHeader("Content-Type", "application/json"));

        // Second request: 200 success
        String successJson = """
                [
                  {"path": "README.md", "type": "blob", "id": "aaa111"}
                ]
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(successJson)
                .addHeader("Content-Type", "application/json"));

        // Need a second page (empty) to stop pagination
        mockWebServer.enqueue(new MockResponse()
                .setBody("[]")
                .addHeader("Content-Type", "application/json"));

        List<TreeEntry> entries = provider.fetchFileTree("owner", "repo", "main");

        // Verify the retry succeeded and returned data
        assertEquals(1, entries.size());
        assertEquals("README.md", entries.get(0).path());

        // Verify at least 2 requests were made (original 429 + retry success)
        assertTrue(mockWebServer.getRequestCount() >= 2);
    }

    @Test
    void allRequests_carryPrivateTokenHeader() throws Exception {
        String json = """
                [
                  {"path": "README.md", "type": "blob", "id": "aaa111"}
                ]
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        // Empty second page to stop pagination
        mockWebServer.enqueue(new MockResponse()
                .setBody("[]")
                .addHeader("Content-Type", "application/json"));

        provider.fetchFileTree("owner", "repo", "main");

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("test-token", request.getHeader("PRIVATE-TOKEN"));
    }
}
