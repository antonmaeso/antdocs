package com.example.documenter.vcsgateway.bitbucket;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BitbucketProvider} — read operations, write operations,
 * rate-limit handling, and auth header verification.
 */
class BitbucketProviderTest {

    private MockWebServer mockWebServer;
    private BitbucketProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        VcsProperties props = new VcsProperties();
        props.setProvider("bitbucket");
        props.setBotUsername("test-bot");

        VcsProperties.BitbucketConfig bitbucketConfig = new VcsProperties.BitbucketConfig();
        bitbucketConfig.setToken("test-token");
        bitbucketConfig.setApiBaseUrl(mockWebServer.url("/").toString());
        props.setBitbucket(bitbucketConfig);

        provider = new BitbucketProvider(props);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ========== Read operations ==========

    @Test
    void fetchFileTree_mapsEntriesWithPagination() {
        // First page with "next" URL pointing to mock server second page
        String page2Url = mockWebServer.url("/repositories/owner/repo/src/main/?page=2").toString();
        String page1 = """
                {
                  "values": [
                    {"path": "src/Main.java", "type": "commit_file", "commit": {"hash": "aaa111"}},
                    {"path": "src", "type": "commit_directory", "commit": {"hash": "bbb222"}}
                  ],
                  "next": "%s"
                }
                """.formatted(page2Url);
        mockWebServer.enqueue(new MockResponse()
                .setBody(page1)
                .addHeader("Content-Type", "application/json"));

        // Second page without "next" — signals end of pagination
        String page2 = """
                {
                  "values": [
                    {"path": "README.md", "type": "commit_file", "commit": {"hash": "ccc333"}}
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(page2)
                .addHeader("Content-Type", "application/json"));

        List<TreeEntry> entries = provider.fetchFileTree("owner", "repo", "main");

        assertEquals(3, entries.size());

        // First page entries
        assertEquals("src/Main.java", entries.get(0).path());
        assertEquals("blob", entries.get(0).type());  // commit_file → blob
        assertEquals("aaa111", entries.get(0).sha());

        assertEquals("src", entries.get(1).path());
        assertEquals("tree", entries.get(1).type());  // commit_directory → tree
        assertEquals("bbb222", entries.get(1).sha());

        // Second page entry
        assertEquals("README.md", entries.get(2).path());
        assertEquals("blob", entries.get(2).type());
        assertEquals("ccc333", entries.get(2).sha());
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
    void listOpenPullRequests_mapsPullRequests() {
        String json = """
                {
                  "values": [
                    {
                      "id": 10,
                      "title": "Add feature X",
                      "source": {"commit": {"hash": "head111"}},
                      "destination": {"commit": {"hash": "base222"}}
                    },
                    {
                      "id": 20,
                      "title": "Fix bug Y",
                      "source": {"commit": {"hash": "head333"}},
                      "destination": {"commit": {"hash": "base444"}}
                    }
                  ]
                }
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
    void fetchPullRequestDiff_parsesUnifiedDiff() {
        String diff = """
                diff --git a/src/Main.java b/src/Main.java
                index 1234567..abcdefg 100644
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1,3 +1,4 @@
                 public class Main {
                +    // new comment
                 }
                diff --git a/src/Utils.java b/src/Utils.java
                index 2345678..bcdefgh 100644
                --- a/src/Utils.java
                +++ b/src/Utils.java
                @@ -1,2 +1,3 @@
                 public class Utils {
                +    // utility
                 }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(diff)
                .addHeader("Content-Type", "text/plain"));

        PullRequestDiff result = provider.fetchPullRequestDiff("owner", "repo", 10);

        assertEquals(10, result.prNumber());
        List<FileDiff> fileDiffs = result.fileDiffs();
        assertEquals(2, fileDiffs.size());

        assertEquals("src/Main.java", fileDiffs.get(0).path());
        assertTrue(fileDiffs.get(0).patch().contains("+++ b/src/Main.java"));

        assertEquals("src/Utils.java", fileDiffs.get(1).path());
        assertTrue(fileDiffs.get(1).patch().contains("+++ b/src/Utils.java"));
    }

    @Test
    void listPullRequestComments_mapsCommentsWithPagination() {
        // First page with "next" URL
        String page2Url = mockWebServer.url("/repositories/owner/repo/pullrequests/10/comments?page=2").toString();
        String page1 = """
                {
                  "values": [
                    {
                      "id": 1001,
                      "content": {"raw": "Looks good to me"},
                      "user": {"display_name": "Alice"}
                    }
                  ],
                  "next": "%s"
                }
                """.formatted(page2Url);
        mockWebServer.enqueue(new MockResponse()
                .setBody(page1)
                .addHeader("Content-Type", "application/json"));

        // Second page without "next"
        String page2 = """
                {
                  "values": [
                    {
                      "id": 1002,
                      "content": {"raw": "Please fix the typo"},
                      "user": {"display_name": "Bob"}
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(page2)
                .addHeader("Content-Type", "application/json"));

        List<PullRequestComment> comments = provider.listPullRequestComments("owner", "repo", 10);

        assertEquals(2, comments.size());

        assertEquals(1001, comments.get(0).id());
        assertEquals("Looks good to me", comments.get(0).body());
        assertEquals("Alice", comments.get(0).author());

        assertEquals(1002, comments.get(1).id());
        assertEquals("Please fix the typo", comments.get(1).body());
        assertEquals("Bob", comments.get(1).author());
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
        assertTrue(request.getPath().contains("/pullrequests/10/comments"));

        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) body.get("content");
        assertEquals("Great work!", content.get("raw"));
    }

    @Test
    void approvePullRequest_sendsCorrectRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json"));

        provider.approvePullRequest("owner", "repo", 10);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().contains("/pullrequests/10/approve"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createRepository_sendsCorrectRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        provider.createRepository("my-workspace", "new-repo", true);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().contains("/repositories/my-workspace/new-repo"));

        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), Map.class);
        assertEquals("git", body.get("scm"));
        assertEquals(true, body.get("is_private"));
    }

    @Test
    void createRepository_silentlySucceedsOn409() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(409)
                .addHeader("Content-Type", "application/json"));

        assertDoesNotThrow(() -> provider.createRepository("my-workspace", "existing-repo", true));
    }

    @Test
    void pushFile_sendsFormData() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json"));

        provider.pushFile("owner", "repo", "README.md", "Hello World", "Add README");

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().contains("/repositories/owner/repo/src"));

        String bodyStr = request.getBody().readUtf8();
        // Form-encoded body should contain the file path as key and content as value
        assertTrue(bodyStr.contains("README.md"));
        assertTrue(bodyStr.contains("Hello+World") || bodyStr.contains("Hello%20World") || bodyStr.contains("Hello World"));
        assertTrue(bodyStr.contains("message"));
        assertTrue(bodyStr.contains("Add+README") || bodyStr.contains("Add%20README") || bodyStr.contains("Add README"));
    }

    // ========== Rate-limit and auth ==========

    @Test
    void rateLimitResponse_retriesAfterSleep() {
        // First request: 429 with Retry-After header
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "1")
                .addHeader("Content-Type", "application/json"));

        // Second request: 200 success (first page)
        String successJson = """
                {
                  "values": [
                    {"path": "README.md", "type": "commit_file", "commit": {"hash": "aaa111"}}
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

        // Verify at least 2 requests were made (original 429 + retry success)
        assertTrue(mockWebServer.getRequestCount() >= 2);
    }

    @Test
    void allRequests_carryBearerTokenHeader() throws Exception {
        String json = """
                {
                  "values": [
                    {"path": "README.md", "type": "commit_file", "commit": {"hash": "aaa111"}}
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        provider.fetchFileTree("owner", "repo", "main");

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("Bearer test-token", request.getHeader("Authorization"));
    }
}
