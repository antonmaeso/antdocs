package com.example.documenter.prreviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mockito;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.documenter.temporalworkflows.workflow.PRReviewWorkflow;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.PullRequestComment;

import io.temporal.client.WorkflowClient;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for the PRCommentPoller.
 *
 * <p>Verifies that signals are sent for every new developer reply and that
 * no duplicate signals are sent when no new comments appear.
 */
class PRCommentPollerPropertyTest {

    private static final String OWNER = "test-owner";
    private static final String REPO = "test-repo";
    private static final String BOT_USERNAME = "doc-bot";
    private static final String WORKFLOW_ID = "pr-review-test-owner-test-repo-1";
    private static final int PR_NUMBER = 1;

    @Provide
    Arbitrary<List<PullRequestComment>> developerComments() {
        Arbitrary<String> bodies = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);
        Arbitrary<Long> ids = Arbitraries.longs().between(1, 1000);
        Arbitrary<String> authors = Arbitraries.of("alice", "bob", "charlie", "developer");

        Arbitrary<PullRequestComment> commentArb = Combinators.combine(bodies, ids, authors)
                .as((body, id, author) -> new PullRequestComment(body, null, 0, author, id));

        // Ensure unique IDs by deduplicating on id
        return commentArb.list().ofMinSize(1).ofMaxSize(15)
                .map(comments -> comments.stream()
                        .collect(Collectors.toMap(PullRequestComment::id, c -> c, (a, b) -> a))
                        .values().stream()
                        .sorted((a, b) -> Long.compare(a.id(), b.id()))
                        .toList());
    }

    @Provide
    Arbitrary<Long> lastSeenIds() {
        return Arbitraries.longs().between(0, 500);
    }

    // ---- Property 15: signalSentForEveryNewDeveloperReply ----

    /**
     * For any set of PR comments where a random subset are new developer replies
     * (author ≠ bot-username, ID > last-seen ID), the PRCommentPoller sends exactly
     * one developerReplySignal to the corresponding workflow instance for each new
     * developer reply detected.
     *
     * <p>Validates: Requirement 6.3
     */
    @Property(tries = 100)
    // Feature: github-repo-documenter, Property 15: signalSentForEveryNewDeveloperReply
    void signalSentForEveryNewDeveloperReply(
            @ForAll("developerComments") List<PullRequestComment> comments,
            @ForAll("lastSeenIds") long lastSeenCommentId) {

        VCSProvider vcsProvider = mock(VCSProvider.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        WorkflowTracker workflowTracker = new WorkflowTracker();
        PRReviewWorkflow workflowStub = mock(PRReviewWorkflow.class);

        workflowTracker.register(OWNER, REPO, PR_NUMBER, WORKFLOW_ID);
        workflowTracker.updateLastSeenCommentId(OWNER, REPO, PR_NUMBER, lastSeenCommentId);

        // Add some bot comments to the mix
        List<PullRequestComment> allComments = new ArrayList<>(comments);
        long maxId = comments.stream().mapToLong(PullRequestComment::id).max().orElse(0);
        allComments.add(new PullRequestComment("Bot says hi", null, 0, BOT_USERNAME, maxId + 1));
        allComments.add(new PullRequestComment("Bot update", null, 0, BOT_USERNAME, maxId + 2));

        when(vcsProvider.listPullRequestComments(OWNER, REPO, PR_NUMBER)).thenReturn(allComments);
        when(workflowClient.newWorkflowStub(eq(PRReviewWorkflow.class), eq(WORKFLOW_ID)))
                .thenReturn(workflowStub);

        PRCommentPoller poller = new PRCommentPoller(vcsProvider, workflowTracker, workflowClient, BOT_USERNAME);
        poller.pollForNewComments();

        // Compute expected new developer replies (non-bot, ID > lastSeen), sorted by ID
        List<String> expectedBodies = comments.stream()
                .filter(c -> c.id() > lastSeenCommentId)
                .filter(c -> !BOT_USERNAME.equals(c.author()))
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .map(PullRequestComment::body)
                .toList();

        // Capture all signals sent and verify count + order
        ArgumentCaptor<String> signalCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(workflowStub, Mockito.times(expectedBodies.size()))
                .developerReplySignal(signalCaptor.capture());

        List<String> actualBodies = signalCaptor.getAllValues();
        assertEquals(expectedBodies, actualBodies,
                "Signals should be sent for every new developer reply in ID order");

        // Verify lastSeenCommentId was updated correctly if there were new replies
        if (!expectedBodies.isEmpty()) {
            long expectedMaxId = comments.stream()
                    .filter(c -> c.id() > lastSeenCommentId)
                    .filter(c -> !BOT_USERNAME.equals(c.author()))
                    .mapToLong(PullRequestComment::id)
                    .max()
                    .orElse(lastSeenCommentId);
            assertEquals(expectedMaxId,
                    workflowTracker.findTrackedPR(OWNER, REPO, PR_NUMBER).get().lastSeenCommentId(),
                    "lastSeenCommentId should be updated to the max new comment ID");
        }
    }

    // ---- Property 16: noDuplicateSignalsOnNoNewComments ----

    /**
     * For any polling cycle where no new comments have appeared since the last cycle,
     * the PRCommentPoller sends zero signals.
     *
     * <p>Validates: Requirement 6.4
     */
    @Property(tries = 100)
    // Feature: github-repo-documenter, Property 16: noDuplicateSignalsOnNoNewComments
    void noDuplicateSignalsOnNoNewComments(
            @ForAll("developerComments") List<PullRequestComment> comments) {

        VCSProvider vcsProvider = mock(VCSProvider.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        WorkflowTracker workflowTracker = new WorkflowTracker();

        workflowTracker.register(OWNER, REPO, PR_NUMBER, WORKFLOW_ID);

        // Set lastSeenCommentId to the max ID in the comment list (all comments are "old")
        long maxCommentId = comments.stream().mapToLong(PullRequestComment::id).max().orElse(0);
        workflowTracker.updateLastSeenCommentId(OWNER, REPO, PR_NUMBER, maxCommentId);

        when(vcsProvider.listPullRequestComments(OWNER, REPO, PR_NUMBER)).thenReturn(comments);

        PRCommentPoller poller = new PRCommentPoller(vcsProvider, workflowTracker, workflowClient, BOT_USERNAME);
        poller.pollForNewComments();

        // No signals should be sent since all comments have ID <= lastSeenCommentId
        verify(workflowClient, never()).newWorkflowStub(eq(PRReviewWorkflow.class), eq(WORKFLOW_ID));

        // lastSeenCommentId should remain unchanged
        assertEquals(maxCommentId,
                workflowTracker.findTrackedPR(OWNER, REPO, PR_NUMBER).get().lastSeenCommentId(),
                "lastSeenCommentId should not change when no new comments exist");
    }
}
