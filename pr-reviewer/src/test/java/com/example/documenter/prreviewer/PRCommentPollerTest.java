package com.example.documenter.prreviewer;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.documenter.prreviewer.domain.TrackedPR;
import com.example.documenter.temporalworkflows.workflow.PRReviewWorkflow;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.VcsApiException;
import com.example.documenter.vcsgateway.domain.PullRequestComment;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;

/**
 * Unit tests for {@link PRCommentPoller}.
 */
@ExtendWith(MockitoExtension.class)
class PRCommentPollerTest {

    @Mock
    private VCSProvider vcsProvider;

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private PRReviewWorkflow workflowStub;

    private WorkflowTracker workflowTracker;
    private PRCommentPoller poller;

    private static final String OWNER = "test-owner";
    private static final String REPO = "test-repo";
    private static final String BOT_USERNAME = "doc-bot";

    @BeforeEach
    void setUp() {
        workflowTracker = new WorkflowTracker();
        poller = new PRCommentPoller(vcsProvider, workflowTracker, workflowClient, BOT_USERNAME);
    }

    @Test
    void newDeveloperReply_signalSentAndLastSeenUpdated() {
        workflowTracker.register(OWNER, REPO, 1, "wf-1");
        PullRequestComment devComment = new PullRequestComment(
                "Here is my reply", null, 0, "developer", 10L);
        when(vcsProvider.listPullRequestComments(OWNER, REPO, 1))
                .thenReturn(List.of(devComment));
        when(workflowClient.newWorkflowStub(PRReviewWorkflow.class, "wf-1"))
                .thenReturn(workflowStub);

        poller.pollForNewComments();

        verify(workflowStub).developerReplySignal("Here is my reply");
        Optional<TrackedPR> tracked = workflowTracker.findTrackedPR(OWNER, REPO, 1);
        assertTrue(tracked.isPresent());
        assertEquals(10L, tracked.get().lastSeenCommentId());
    }

    @Test
    void botComment_noSignalSent() {
        workflowTracker.register(OWNER, REPO, 1, "wf-1");
        PullRequestComment botComment = new PullRequestComment(
                "Bot response", null, 0, BOT_USERNAME, 10L);
        when(vcsProvider.listPullRequestComments(OWNER, REPO, 1))
                .thenReturn(List.of(botComment));

        poller.pollForNewComments();

        verify(workflowClient, never()).newWorkflowStub(eq(PRReviewWorkflow.class), anyString());
    }

    @Test
    void alreadySeenComment_noSignalSent() {
        workflowTracker.register(OWNER, REPO, 1, "wf-1");
        workflowTracker.updateLastSeenCommentId(OWNER, REPO, 1, 10L);
        PullRequestComment oldComment = new PullRequestComment(
                "Old reply", null, 0, "developer", 10L);
        when(vcsProvider.listPullRequestComments(OWNER, REPO, 1))
                .thenReturn(List.of(oldComment));

        poller.pollForNewComments();

        verify(workflowClient, never()).newWorkflowStub(eq(PRReviewWorkflow.class), anyString());
    }

    @Test
    void multipleNewReplies_signalsSentInOrder() {
        workflowTracker.register(OWNER, REPO, 1, "wf-1");
        PullRequestComment comment1 = new PullRequestComment("Reply 1", null, 0, "dev", 5L);
        PullRequestComment comment2 = new PullRequestComment("Reply 2", null, 0, "dev", 10L);
        when(vcsProvider.listPullRequestComments(OWNER, REPO, 1))
                .thenReturn(List.of(comment1, comment2));
        when(workflowClient.newWorkflowStub(PRReviewWorkflow.class, "wf-1"))
                .thenReturn(workflowStub);

        poller.pollForNewComments();

        var inOrder = inOrder(workflowStub);
        inOrder.verify(workflowStub).developerReplySignal("Reply 1");
        inOrder.verify(workflowStub).developerReplySignal("Reply 2");

        Optional<TrackedPR> tracked = workflowTracker.findTrackedPR(OWNER, REPO, 1);
        assertTrue(tracked.isPresent());
        assertEquals(10L, tracked.get().lastSeenCommentId());
    }

    @Test
    void workflowNotFound_warningLoggedAndPRRemovedFromTracker() {
        workflowTracker.register(OWNER, REPO, 1, "wf-1");
        PullRequestComment devComment = new PullRequestComment(
                "Reply", null, 0, "developer", 10L);
        when(vcsProvider.listPullRequestComments(OWNER, REPO, 1))
                .thenReturn(List.of(devComment));
        when(workflowClient.newWorkflowStub(PRReviewWorkflow.class, "wf-1"))
                .thenReturn(workflowStub);
        doThrow(new WorkflowNotFoundException(
                WorkflowExecution.newBuilder().setWorkflowId("wf-1").build(), "test-type", null))
                .when(workflowStub).developerReplySignal(any());

        poller.pollForNewComments();

        assertTrue(workflowTracker.findWorkflowId(OWNER, REPO, 1).isEmpty());
    }

    @Test
    void vcsErrorOnListComments_logAndContinueToNextPR() {
        workflowTracker.register(OWNER, REPO, 1, "wf-1");
        workflowTracker.register(OWNER, REPO, 2, "wf-2");

        // PR #1 fails, PR #2 succeeds
        when(vcsProvider.listPullRequestComments(OWNER, REPO, 1))
                .thenThrow(new VcsApiException("API error", 500));
        PullRequestComment devComment = new PullRequestComment(
                "Reply on PR 2", null, 0, "developer", 10L);
        when(vcsProvider.listPullRequestComments(OWNER, REPO, 2))
                .thenReturn(List.of(devComment));
        when(workflowClient.newWorkflowStub(PRReviewWorkflow.class, "wf-2"))
                .thenReturn(workflowStub);

        poller.pollForNewComments();

        // PR #1 should still be tracked (error doesn't remove it)
        assertTrue(workflowTracker.findWorkflowId(OWNER, REPO, 1).isPresent());
        // PR #2 signal should have been sent
        verify(workflowStub).developerReplySignal("Reply on PR 2");
    }
}
