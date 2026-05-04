package com.example.documenter.prreviewer;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.documenter.prreviewer.domain.TrackedPR;
import com.example.documenter.temporalworkflows.domain.PRReviewState;
import com.example.documenter.temporalworkflows.workflow.PRReviewWorkflow;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.FileDiff;
import com.example.documenter.vcsgateway.domain.PullRequest;
import com.example.documenter.vcsgateway.domain.PullRequestDiff;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

/**
 * Scheduled component that detects new pull requests and starts a
 * {@link PRReviewWorkflow} for each one not already tracked.
 *
 * <p>Also detects closed/merged PRs and removes them from the {@link WorkflowTracker}.
 */
@Component
public class PRReviewer {

    private static final Logger log = LoggerFactory.getLogger(PRReviewer.class);

    private final VCSProvider vcsProvider;
    private final WorkflowTracker workflowTracker;
    private final WorkflowClient workflowClient;
    private final String owner;
    private final String repo;
    private final String ref;

    public PRReviewer(VCSProvider vcsProvider,
                      WorkflowTracker workflowTracker,
                      WorkflowClient workflowClient,
                      @Value("${target-repository.owner}") String owner,
                      @Value("${target-repository.repo}") String repo,
                      @Value("${target-repository.ref:main}") String ref) {
        this.vcsProvider = vcsProvider;
        this.workflowTracker = workflowTracker;
        this.workflowClient = workflowClient;
        this.owner = owner;
        this.repo = repo;
        this.ref = ref;
    }

    @Scheduled(fixedDelayString = "${polling.interval-seconds}000")
    public void pollForNewPRs() {
        List<PullRequest> openPRs;
        try {
            openPRs = vcsProvider.listOpenPullRequests(owner, repo);
        } catch (Exception e) {
            log.error("Failed to list open PRs for {}/{}: {}", owner, repo, e.getMessage());
            return;
        }

        Set<Integer> openPrNumbers = openPRs.stream()
                .map(PullRequest::number)
                .collect(Collectors.toSet());

        // Start workflows for new PRs
        for (PullRequest pr : openPRs) {
            if (workflowTracker.findWorkflowId(owner, repo, pr.number()).isPresent()) {
                continue; // already tracked
            }
            startWorkflowForPR(pr);
        }

        // Remove closed PRs from tracker
        Set<TrackedPR> tracked = workflowTracker.allTracked();
        for (TrackedPR trackedPR : tracked) {
            if (trackedPR.owner().equals(owner) && trackedPR.repo().equals(repo)
                    && !openPrNumbers.contains(trackedPR.prNumber())) {
                log.info("PR #{} in {}/{} is no longer open — removing from tracker",
                        trackedPR.prNumber(), owner, repo);
                workflowTracker.remove(owner, repo, trackedPR.prNumber());
            }
        }
    }

    private void startWorkflowForPR(PullRequest pr) {
        String workflowId = "pr-review-" + owner + "-" + repo + "-" + pr.number();

        try {
            PullRequestDiff diff = vcsProvider.fetchPullRequestDiff(owner, repo, pr.number());
            String diffPatch = diff.fileDiffs().stream()
                    .map(FileDiff::patch)
                    .collect(Collectors.joining("\n"));

            PRReviewState state = new PRReviewState(
                    owner, repo, pr.number(), diffPatch, List.of(), List.of());

            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue("pr-review-queue")
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                    .build();

            PRReviewWorkflow workflow = workflowClient.newWorkflowStub(PRReviewWorkflow.class, options);
            WorkflowClient.start(workflow::start, state);

            workflowTracker.register(owner, repo, pr.number(), workflowId);
            log.info("Started PR review workflow {} for PR #{} in {}/{}",
                    workflowId, pr.number(), owner, repo);

        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("Workflow {} already running — registering in tracker", workflowId);
            workflowTracker.register(owner, repo, pr.number(), workflowId);
        } catch (Exception e) {
            log.error("Failed to start workflow for PR #{} in {}/{}: {}",
                    pr.number(), owner, repo, e.getMessage());
        }
    }
}
