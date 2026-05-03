package com.example.documenter.temporalworkflows.activity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.aigateway.domain.DocUpdate;
import com.example.documenter.aigateway.domain.RelevanceScanResult;
import com.example.documenter.aigateway.domain.ReviewDecision;
import com.example.documenter.temporalworkflows.activity.impl.AnalyseDiffActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.ApprovePRActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.PostCommentActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.ScanRelevantDocsActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.TimeoutActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.UpdateDocumentationActivityImpl;
import com.example.documenter.temporalworkflows.domain.ConversationTurn;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.PullRequestComment;
import com.example.documenter.vcsgateway.domain.TreeEntry;

/**
 * Unit tests for all six Temporal activity implementations.
 *
 * <p>Each activity is tested in isolation with mocked {@link VCSProvider} and {@link AIGateway}
 * dependencies — no Temporal test environment needed.</p>
 */
@ExtendWith(MockitoExtension.class)
class ActivityUnitTest {

    // ========================================================================
    // ScanRelevantDocsActivityImpl
    // ========================================================================

    @Test
    void scanActivity_fetchesDocTreeAndCallsAIGateway() {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);

        // Companion repo tree has two markdown blobs and one non-markdown file
        List<TreeEntry> tree = List.of(
                new TreeEntry("docs/api.md", "blob", "sha1"),
                new TreeEntry("docs/readme.md", "blob", "sha2"),
                new TreeEntry("docs/images", "tree", "sha3"),
                new TreeEntry("docs/config.yml", "blob", "sha4")
        );
        when(vcsProvider.fetchFileTree("owner", "repo_ai_documentation", "main")).thenReturn(tree);
        when(vcsProvider.fetchFileContent("owner", "repo_ai_documentation", "docs/api.md", "main"))
                .thenReturn("# API Docs\nAPI documentation for the project.");
        when(vcsProvider.fetchFileContent("owner", "repo_ai_documentation", "docs/readme.md", "main"))
                .thenReturn("# README\nProject overview.");

        List<String> relevantPaths = List.of("docs/api.md");
        when(aiGateway.scanRelevantDocs(anyString(), anyList()))
                .thenReturn(new RelevanceScanResult(relevantPaths));

        ScanRelevantDocsActivityImpl activity = new ScanRelevantDocsActivityImpl(vcsProvider, aiGateway);
        RelevanceScanResult result = activity.scan("diff-patch", "owner", "repo");

        // Verify companion repo tree was fetched
        verify(vcsProvider).fetchFileTree("owner", "repo_ai_documentation", "main");
        // Verify only .md blobs had content fetched
        verify(vcsProvider).fetchFileContent("owner", "repo_ai_documentation", "docs/api.md", "main");
        verify(vcsProvider).fetchFileContent("owner", "repo_ai_documentation", "docs/readme.md", "main");
        verify(vcsProvider, never()).fetchFileContent("owner", "repo_ai_documentation", "docs/config.yml", "main");
        // Verify AI gateway called with summaries
        verify(aiGateway).scanRelevantDocs(eq("diff-patch"), argThat(summaries -> summaries.size() == 2));
        assertEquals(relevantPaths, result.relevantPaths());
    }

    // ========================================================================
    // AnalyseDiffActivityImpl
    // ========================================================================

    @Test
    void analyseActivity_fetchesDocContentAndCallsAIGateway() {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);

        when(vcsProvider.fetchFileContent("owner", "repo_ai_documentation", "docs/api.md", "main"))
                .thenReturn("# API Docs content");

        List<DocUpdate> updates = List.of(new DocUpdate("docs/api.md", "updated content"));
        when(aiGateway.analyseAndDecide(anyString(), anyList(), anyList()))
                .thenReturn(new ReviewDecision.Autonomous(updates));

        AnalyseDiffActivityImpl activity = new AnalyseDiffActivityImpl(vcsProvider, aiGateway);
        List<ConversationTurn> history = List.of(
                new ConversationTurn("Q1?", "A1")
        );
        ReviewDecision result = activity.analyse("diff-patch", List.of("docs/api.md"), history, "owner", "repo");

        // Verify doc content fetched from companion repo
        verify(vcsProvider).fetchFileContent("owner", "repo_ai_documentation", "docs/api.md", "main");
        // Verify AI gateway called with correct args
        verify(aiGateway).analyseAndDecide(
                eq("diff-patch"),
                argThat(docs -> docs.size() == 1 && docs.get(0).path().equals("docs/api.md")),
                argThat(h -> h.size() == 1 && h.get(0).question().equals("Q1?"))
        );
        assertInstanceOf(ReviewDecision.Autonomous.class, result);
    }

    @Test
    void analyseActivity_returnsNeedsInput() {
        VCSProvider vcsProvider = mock(VCSProvider.class);
        AIGateway aiGateway = mock(AIGateway.class);

        when(vcsProvider.fetchFileContent("owner", "repo_ai_documentation", "docs/api.md", "main"))
                .thenReturn("content");

        when(aiGateway.analyseAndDecide(anyString(), anyList(), anyList()))
                .thenReturn(new ReviewDecision.NeedsInput("What does this do?"));

        AnalyseDiffActivityImpl activity = new AnalyseDiffActivityImpl(vcsProvider, aiGateway);
        ReviewDecision result = activity.analyse("diff", List.of("docs/api.md"), List.of(), "owner", "repo");

        assertInstanceOf(ReviewDecision.NeedsInput.class, result);
        assertEquals("What does this do?", ((ReviewDecision.NeedsInput) result).question());
    }

    // ========================================================================
    // PostCommentActivityImpl
    // ========================================================================

    @Test
    void postCommentActivity_postsCommentWithCorrectArgs() {
        VCSProvider vcsProvider = mock(VCSProvider.class);

        PostCommentActivityImpl activity = new PostCommentActivityImpl(vcsProvider);
        activity.post("owner", "repo", 42, "What does this function do?");

        ArgumentCaptor<PullRequestComment> captor = ArgumentCaptor.forClass(PullRequestComment.class);
        verify(vcsProvider).postPullRequestComment(eq("owner"), eq("repo"), eq(42), captor.capture());

        PullRequestComment posted = captor.getValue();
        assertEquals("What does this function do?", posted.body());
    }

    // ========================================================================
    // UpdateDocumentationActivityImpl
    // ========================================================================

    @Test
    void updateDocsActivity_pushesFileForEachDocUpdate() {
        VCSProvider vcsProvider = mock(VCSProvider.class);

        UpdateDocumentationActivityImpl activity = new UpdateDocumentationActivityImpl(vcsProvider);
        List<DocUpdate> updates = List.of(
                new DocUpdate("docs/api.md", "new api content"),
                new DocUpdate("docs/readme.md", "new readme content")
        );
        activity.update(updates, "owner", "repo");

        // Verify pushFile called for each update on the companion repo
        verify(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"), eq("docs/api.md"),
                eq("new api content"), contains("docs/api.md"));
        verify(vcsProvider).pushFile(eq("owner"), eq("repo_ai_documentation"), eq("docs/readme.md"),
                eq("new readme content"), contains("docs/readme.md"));
        verify(vcsProvider, times(2)).pushFile(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ========================================================================
    // ApprovePRActivityImpl
    // ========================================================================

    @Test
    void approveActivity_callsApprovePullRequest() {
        VCSProvider vcsProvider = mock(VCSProvider.class);

        ApprovePRActivityImpl activity = new ApprovePRActivityImpl(vcsProvider);
        activity.approve("owner", "repo", 42);

        verify(vcsProvider).approvePullRequest("owner", "repo", 42);
    }

    // ========================================================================
    // TimeoutActivityImpl
    // ========================================================================

    @Test
    void timeoutActivity_postsTimeoutComment() {
        VCSProvider vcsProvider = mock(VCSProvider.class);

        TimeoutActivityImpl activity = new TimeoutActivityImpl(vcsProvider);
        activity.timeout("owner", "repo", 42, "What does this function do?");

        ArgumentCaptor<PullRequestComment> captor = ArgumentCaptor.forClass(PullRequestComment.class);
        verify(vcsProvider).postPullRequestComment(eq("owner"), eq("repo"), eq(42), captor.capture());

        PullRequestComment posted = captor.getValue();
        assertTrue(posted.body().contains("timeout"), "Timeout message should mention timeout");
        assertTrue(posted.body().contains("What does this function do?"),
                "Timeout message should include the last question");
    }
}
