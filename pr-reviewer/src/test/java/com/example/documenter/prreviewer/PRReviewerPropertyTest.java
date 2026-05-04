package com.example.documenter.prreviewer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.documenter.temporalworkflows.workflow.PRReviewWorkflow;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.FileDiff;
import com.example.documenter.vcsgateway.domain.PullRequest;
import com.example.documenter.vcsgateway.domain.PullRequestDiff;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for the PRReviewer module.
 *
 * <p>Uses jqwik to verify that workflows are started for every untracked PR
 * and never for already-tracked PRs.
 */
class PRReviewerPropertyTest {

    private static final String OWNER = "test-owner";
    private static final String REPO = "test-repo";
    private static final String REF = "main";

    @Provide
    Arbitrary<List<PullRequest>> openPullRequests() {
        Arbitrary<Integer> prNumbers = Arbitraries.integers().between(1, 500);
        Arbitrary<String> titles = Arbitraries.of("Fix bug", "Add feature", "Refactor", "Update docs", "Hotfix");
        Arbitrary<String> shas = Arbitraries.strings().alpha().ofLength(8);

        Arbitrary<PullRequest> prArbitrary = Combinators.combine(prNumbers, titles, shas, shas)
                .as(PullRequest::new);

        // Ensure unique PR numbers by deduplicating
        return prArbitrary.list().ofMinSize(1).ofMaxSize(10)
                .map(prs -> prs.stream()
                        .collect(Collectors.toMap(PullRequest::number, pr -> pr, (a, b) -> a))
                        .values().stream().toList());
    }

    @Provide
    Arbitrary<Set<Integer>> trackedPrNumbers() {
        return Arbitraries.integers().between(1, 500).set().ofMinSize(0).ofMaxSize(5);
    }

    // ---- Property 10: workflowStartedForEveryUntrackedPR ----

    /**
     * For any list of open PRs where a random subset are already tracked,
     * the PRReviewer starts exactly one workflow for each untracked PR and
     * does not start a workflow for any already-tracked PR.
     *
     * <p>Validates: Requirements 4.2, 4.5
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 10: workflowStartedForEveryUntrackedPR
    void workflowStartedForEveryUntrackedPR(
            @ForAll("openPullRequests") List<PullRequest> openPRs,
            @ForAll("trackedPrNumbers") Set<Integer> alreadyTracked) {

        VCSProvider vcsProvider = mock(VCSProvider.class);
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        WorkflowTracker workflowTracker = new WorkflowTracker();

        // Pre-register a subset of PRs as already tracked
        Set<Integer> preTrackedInOpenList = new HashSet<>();
        for (PullRequest pr : openPRs) {
            if (alreadyTracked.contains(pr.number())) {
                workflowTracker.register(OWNER, REPO, pr.number(), "existing-wf-" + pr.number());
                preTrackedInOpenList.add(pr.number());
            }
        }

        // Mock VCS to return the open PRs
        when(vcsProvider.listOpenPullRequests(OWNER, REPO)).thenReturn(openPRs);

        // Mock diff fetch for each untracked PR
        for (PullRequest pr : openPRs) {
            if (!preTrackedInOpenList.contains(pr.number())) {
                PullRequestDiff diff = new PullRequestDiff(pr.number(),
                        List.of(new FileDiff("src/File.java", "+ change")));
                when(vcsProvider.fetchPullRequestDiff(OWNER, REPO, pr.number())).thenReturn(diff);
            }
        }

        // Mock workflow stub creation
        PRReviewWorkflow workflowStub = mock(PRReviewWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(PRReviewWorkflow.class), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);

        PRReviewer prReviewer = new PRReviewer(vcsProvider, workflowTracker, workflowClient, OWNER, REPO, REF);
        prReviewer.pollForNewPRs();

        // Every open PR should now be tracked
        for (PullRequest pr : openPRs) {
            assertTrue(workflowTracker.findWorkflowId(OWNER, REPO, pr.number()).isPresent(),
                    "PR #" + pr.number() + " should be tracked after polling");
        }

        // Untracked PRs should have the deterministic workflow ID
        for (PullRequest pr : openPRs) {
            if (!preTrackedInOpenList.contains(pr.number())) {
                String expectedWfId = "pr-review-" + OWNER + "-" + REPO + "-" + pr.number();
                assertEquals(expectedWfId,
                        workflowTracker.findWorkflowId(OWNER, REPO, pr.number()).get(),
                        "Workflow ID for new PR #" + pr.number() + " should follow deterministic pattern");
            }
        }

        // Already-tracked PRs should retain their original workflow ID
        for (Integer prNumber : preTrackedInOpenList) {
            assertEquals("existing-wf-" + prNumber,
                    workflowTracker.findWorkflowId(OWNER, REPO, prNumber).get(),
                    "Already-tracked PR #" + prNumber + " should retain its original workflow ID");
        }

        // Count: total tracked should equal total open PRs
        long totalTracked = openPRs.stream()
                .filter(pr -> workflowTracker.findWorkflowId(OWNER, REPO, pr.number()).isPresent())
                .count();
        assertEquals(openPRs.size(), totalTracked,
                "All open PRs should be tracked after polling");
    }
}
