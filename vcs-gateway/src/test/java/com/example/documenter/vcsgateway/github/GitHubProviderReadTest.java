package com.example.documenter.vcsgateway.github;

import com.example.documenter.vcsgateway.config.VcsProperties;
import com.example.documenter.vcsgateway.domain.FileDiff;
import com.example.documenter.vcsgateway.domain.PullRequest;
import com.example.documenter.vcsgateway.domain.PullRequestComment;
import com.example.documenter.vcsgateway.domain.PullRequestDiff;
import com.example.documenter.vcsgateway.domain.TreeEntry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitHubProvider} read operations using MockWebServer.
 */
class GitHubProviderReadTest {

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

    // ---- fetchFileTree ----

    @Test
    void fetchFileTree_mapsTreeEntries() {
        String json = """
                {
                  "sha": "abc123",
                  "tree": [
                    {"path": "src/Main.java", "type": "blob", "sha": "aaa111"},
                    {"path": "src", "type": "tree", "sha": "bbb222"},
                    {"path": "lib/external", "type": "commit", "sha": "ccc333"}
                  ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        List<TreeEntry> entries = provider.fetchFileTree("owner", "repo", "main");

        assertEquals(3, entries.size());

        assertEquals("src/Main.java", entries.get(0).path());
        assertEquals("blob", entries.get(0).type());
        assertEquals("aaa111", entries.get(0).sha());

        assertEquals("src", entries.get(1).path());
        assertEquals("tree", entries.get(1).type());
        assertEquals("bbb222", entries.get(1).sha());

        assertEquals("lib/external", entries.get(2).path());
        assertEquals("commit", entries.get(2).type());
        assertEquals("ccc333", entries.get(2).sha());
    }

    @Test
    void fetchFileTree_emptyTree() {
        String json = """
                {
                  "sha": "abc123",
                  "tree": []
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        List<TreeEntry> entries = provider.fetchFileTree("owner", "repo", "main");

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    // ---- fetchFileContent ----

    @Test
    void fetchFileContent_decodesBase64() {
        String originalContent = "public class Hello { }";
        String encoded = Base64.getEncoder().encodeToString(originalContent.getBytes());
        // GitHub returns base64 with newlines — simulate with \\n in JSON
        String encodedWithNewlines = encoded.substring(0, Math.min(10, encoded.length()))
                + "\\n" + encoded.substring(Math.min(10, encoded.length()));

        String json = """
                {
                  "name": "Hello.java",
                  "path": "src/Hello.java",
                  "content": "%s",
                  "encoding": "base64"
                }
                """.formatted(encodedWithNewlines);
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        String content = provider.fetchFileContent("owner", "repo", "src/Hello.java", "main");

        assertEquals(originalContent, content);
    }

    // ---- listOpenPullRequests ----

    @Test
    void listOpenPullRequests_mapsPullRequests() {
        String json = """
                [
                  {
                    "number": 42,
                    "title": "Add feature X",
                    "head": {"sha": "head111"},
                    "base": {"sha": "base222"}
                  },
                  {
                    "number": 99,
                    "title": "Fix bug Y",
                    "head": {"sha": "head333"},
                    "base": {"sha": "base444"}
                  }
                ]
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        List<PullRequest> prs = provider.listOpenPullRequests("owner", "repo");

        assertEquals(2, prs.size());

        assertEquals(42, prs.get(0).number());
        assertEquals("Add feature X", prs.get(0).title());
        assertEquals("head111", prs.get(0).headSha());
        assertEquals("base222", prs.get(0).baseSha());

        assertEquals(99, prs.get(1).number());
        assertEquals("Fix bug Y", prs.get(1).title());
        assertEquals("head333", prs.get(1).headSha());
        assertEquals("base444", prs.get(1).baseSha());
    }

    @Test
    void listOpenPullRequests_emptyList() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("[]")
                .addHeader("Content-Type", "application/json"));

        List<PullRequest> prs = provider.listOpenPullRequests("owner", "repo");

        assertNotNull(prs);
        assertTrue(prs.isEmpty());
    }

    // ---- fetchPullRequestDiff ----

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
                .addHeader("Content-Type", "application/vnd.github.diff"));

        PullRequestDiff result = provider.fetchPullRequestDiff("owner", "repo", 42);

        assertEquals(42, result.prNumber());
        List<FileDiff> fileDiffs = result.fileDiffs();
        assertEquals(2, fileDiffs.size());
        assertEquals("src/Main.java", fileDiffs.get(0).path());
        assertEquals("src/Utils.java", fileDiffs.get(1).path());
        // Each patch should contain the diff content
        assertTrue(fileDiffs.get(0).patch().contains("+++ b/src/Main.java"));
        assertTrue(fileDiffs.get(1).patch().contains("+++ b/src/Utils.java"));
    }

    @Test
    void fetchPullRequestDiff_emptyDiff() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("")
                .addHeader("Content-Type", "application/vnd.github.diff"));

        PullRequestDiff result = provider.fetchPullRequestDiff("owner", "repo", 42);

        assertEquals(42, result.prNumber());
        assertNotNull(result.fileDiffs());
        assertTrue(result.fileDiffs().isEmpty());
    }

    // ---- listPullRequestComments ----

    @Test
    void listPullRequestComments_mapsComments() {
        String json = """
                [
                  {
                    "id": 1001,
                    "body": "Looks good to me",
                    "user": {"login": "alice"}
                  },
                  {
                    "id": 1002,
                    "body": "Please fix the typo",
                    "user": {"login": "bob"}
                  }
                ]
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        List<PullRequestComment> comments = provider.listPullRequestComments("owner", "repo", 42);

        assertEquals(2, comments.size());

        assertEquals(1001, comments.get(0).id());
        assertEquals("Looks good to me", comments.get(0).body());
        assertEquals("alice", comments.get(0).author());

        assertEquals(1002, comments.get(1).id());
        assertEquals("Please fix the typo", comments.get(1).body());
        assertEquals("bob", comments.get(1).author());
    }

    @Test
    void listPullRequestComments_commentWithNoLineReference() {
        // Issue comments from GitHub don't have path/line — GitHubProvider maps them to null/0
        String json = """
                [
                  {
                    "id": 2001,
                    "body": "General comment on the PR",
                    "user": {"login": "charlie"}
                  }
                ]
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        List<PullRequestComment> comments = provider.listPullRequestComments("owner", "repo", 10);

        assertEquals(1, comments.size());
        PullRequestComment comment = comments.get(0);
        assertNull(comment.path(), "Issue comments should have null path");
        assertEquals(0, comment.line(), "Issue comments should have line = 0");
        assertEquals("General comment on the PR", comment.body());
        assertEquals("charlie", comment.author());
        assertEquals(2001, comment.id());
    }
}
