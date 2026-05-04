package com.example.documenter.prreviewer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.documenter.temporalworkflows.workflow.PRReviewWorkflow;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.VcsApiException;
import com.example.documenter.vcsgateway.domain.FileDiff;
import com.example.documenter.vcsgateway.domain.PullRequest;
import com.example.documenter.vcsgateway.domain.PullRequestDiff;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

/**
 * Unit tests for {@link PRReviewer}.
 */
@ExtendWith(MockitoExtension.class)
class PRReviewerTest {

    @Mock
    private VCSProvider vcsProvider;

    @Mock
    private WorkflowClient workflowClient;

    private WorkflowTracker workflowTracker;
    private PRReviewer prReviewer;

    private static final String OWNER = "test-owner";
    private static final String REPO = "test-repo";
    private static final String REF = "main";

    @BeforeEach
    void setUp() {
        workflowTracker = new WorkflowTracker();
        prReviewer = new PRReviewer(vcsProvider, workflowTracker, workflowClient, OWNER, REPO, REF);
    }

    @Test
    void newPRNotInTracker_workflowStartedAndRegistered() {
        PullRequest pr = new PullRequest(42, "Add feature", "head-sha", "base-sha");
        PullRequestDiff diff = new PullRequestDiff(42, List.of(
                new FileDiff("src/Main.java", "+ added line")));

        when(vcsProvider.listOpenPullRequests(OWNER, REPO)).thenReturn(List.of(pr));
        when(vcsProvider.fetchPullRequestDiff(OWNER, REPO, 42)).thenReturn(diff);

        PRReviewWorkflow workflowStub = mock(PRReviewWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(PRReviewWorkflow.class), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);

        prReviewer.pollForNewPRs();

        verify(workflowClient).newWorkflowStub(eq(PRReviewWorkflow.class), any(WorkflowOptions.class));
        assertTrue(workflowTracker.findWorkflowId(OWNER, REPO, 42).isPresent());
        assertEquals("pr-review-test-owner-test-repo-42",
                workflowTracker.findWorkflowId(OWNER, REPO, 42).get());
    }

    @Test
    void prAlreadyInTracker_workflowNotStarted() {
        workflowTracker.register(OWNER, REPO, 42, "existing-wf-id");
        PullRequest pr = new PullRequest(42, "Add feature", "head-sha", "base-sha");

        when(vcsProvider.listOpenPullRequests(OWNER, REPO)).thenReturn(List.of(pr));

        prReviewer.pollForNewPRs();

        verify(workflowClient, never()).newWorkflowStub(eq(PRReviewWorkflow.class), any(WorkflowOptions.class));
        // Tracker should still have the original entry
        assertEquals("existing-wf-id", workflowTracker.findWorkflowId(OWNER, REPO, 42).get());
    }

    @Test
    void vcsErrorOnListPRs_cycleSkippedTrackerUnchanged() {
        workflowTracker.register(OWNER, REPO, 10, "wf-10");

        when(vcsProvider.listOpenPullRequests(OWNER, REPO))
                .thenThrow(new VcsApiException("API error", 500));

        prReviewer.pollForNewPRs();

        // Tracker should remain unchanged
        assertTrue(workflowTracker.findWorkflowId(OWNER, REPO, 10).isPresent());
        assertEquals("wf-10", workflowTracker.findWorkflowId(OWNER, REPO, 10).get());
        // No workflow should have been started
        verify(workflowClient, never()).newWorkflowStub(eq(PRReviewWorkflow.class), any(WorkflowOptions.class));
        verify(vcsProvider, never()).fetchPullRequestDiff(anyString(), anyString(), anyInt());
    }

    @Test
    void prClosed_removedFromTracker() {
        workflowTracker.register(OWNER, REPO, 42, "wf-42");

        // Return empty list — PR #42 is no longer open
        when(vcsProvider.listOpenPullRequests(OWNER, REPO)).thenReturn(List.of());

        prReviewer.pollForNewPRs();

        assertTrue(workflowTracker.findWorkflowId(OWNER, REPO, 42).isEmpty());
    }

    @Test
    void multipleNewPRs_oneWorkflowPerPR() {
        PullRequest pr1 = new PullRequest(1, "PR one", "sha1", "base1");
        PullRequest pr2 = new PullRequest(2, "PR two", "sha2", "base2");
        PullRequest pr3 = new PullRequest(3, "PR three", "sha3", "base3");

        PullRequestDiff diff1 = new PullRequestDiff(1, List.of(new FileDiff("a.java", "+a")));
        PullRequestDiff diff2 = new PullRequestDiff(2, List.of(new FileDiff("b.java", "+b")));
        PullRequestDiff diff3 = new PullRequestDiff(3, List.of(new FileDiff("c.java", "+c")));

        when(vcsProvider.listOpenPullRequests(OWNER, REPO)).thenReturn(List.of(pr1, pr2, pr3));
        when(vcsProvider.fetchPullRequestDiff(OWNER, REPO, 1)).thenReturn(diff1);
        when(vcsProvider.fetchPullRequestDiff(OWNER, REPO, 2)).thenReturn(diff2);
        when(vcsProvider.fetchPullRequestDiff(OWNER, REPO, 3)).thenReturn(diff3);

        PRReviewWorkflow stub = mock(PRReviewWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(PRReviewWorkflow.class), any(WorkflowOptions.class)))
                .thenReturn(stub);

        prReviewer.pollForNewPRs();

        // All three PRs should be tracked
        assertTrue(workflowTracker.findWorkflowId(OWNER, REPO, 1).isPresent());
        assertTrue(workflowTracker.findWorkflowId(OWNER, REPO, 2).isPresent());
        assertTrue(workflowTracker.findWorkflowId(OWNER, REPO, 3).isPresent());

        assertEquals("pr-review-test-owner-test-repo-1",
                workflowTracker.findWorkflowId(OWNER, REPO, 1).get());
        assertEquals("pr-review-test-owner-test-repo-2",
                workflowTracker.findWorkflowId(OWNER, REPO, 2).get());
        assertEquals("pr-review-test-owner-test-repo-3",
                workflowTracker.findWorkflowId(OWNER, REPO, 3).get());
    }
}
