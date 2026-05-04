package com.example.documenter.prreviewer;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.documenter.prreviewer.domain.TrackedPR;
import com.example.documenter.temporalworkflows.workflow.PRReviewWorkflow;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.PullRequestComment;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;

/**
 * Scheduled component that polls for new developer comments on tracked PRs
 * and sends {@code developerReplySignal} to the corresponding Temporal workflow.
 */
@Component
public class PRCommentPoller {

    private static final Logger log = LoggerFactory.getLogger(PRCommentPoller.class);

    private final VCSProvider vcsProvider;
    private final WorkflowTracker workflowTracker;
    private final WorkflowClient workflowClient;
    private final String botUsername;

    public PRCommentPoller(VCSProvider vcsProvider,
                           WorkflowTracker workflowTracker,
                           WorkflowClient workflowClient,
                           @Value("${vcs.bot-username}") String botUsername) {
        this.vcsProvider = vcsProvider;
        this.workflowTracker = workflowTracker;
        this.workflowClient = workflowClient;
        this.botUsername = botUsername;
    }

    @Scheduled(fixedDelayString = "${polling.interval-seconds}000")
    public void pollForNewComments() {
        Set<TrackedPR> trackedPRs = workflowTracker.allTracked();

        for (TrackedPR trackedPR : trackedPRs) {
            processCommentsForPR(trackedPR);
        }
    }

    private void processCommentsForPR(TrackedPR trackedPR) {
        List<PullRequestComment> comments;
        try {
            comments = vcsProvider.listPullRequestComments(
                    trackedPR.owner(), trackedPR.repo(), trackedPR.prNumber());
        } catch (Exception e) {
            log.error("Failed to list comments for PR #{} in {}/{}: {}",
                    trackedPR.prNumber(), trackedPR.owner(), trackedPR.repo(), e.getMessage());
            return;
        }

        // Filter new non-bot comments
        List<PullRequestComment> newDeveloperComments = comments.stream()
                .filter(c -> c.id() > trackedPR.lastSeenCommentId())
                .filter(c -> !botUsername.equals(c.author()))
                .sorted(Comparator.comparingLong(PullRequestComment::id))
                .toList();

        if (newDeveloperComments.isEmpty()) {
            return;
        }

        for (PullRequestComment comment : newDeveloperComments) {
            try {
                PRReviewWorkflow workflowStub = workflowClient.newWorkflowStub(
                        PRReviewWorkflow.class, trackedPR.workflowId());
                workflowStub.developerReplySignal(comment.body());
                log.debug("Sent developerReplySignal for comment {} on PR #{} in {}/{}",
                        comment.id(), trackedPR.prNumber(), trackedPR.owner(), trackedPR.repo());
            } catch (WorkflowNotFoundException e) {
                log.warn("Workflow {} not found for PR #{} in {}/{} — removing from tracker",
                        trackedPR.workflowId(), trackedPR.prNumber(),
                        trackedPR.owner(), trackedPR.repo());
                workflowTracker.remove(trackedPR.owner(), trackedPR.repo(), trackedPR.prNumber());
                return; // stop processing comments for this PR
            }
        }

        // Update lastSeenCommentId to the max comment ID seen in this cycle
        long maxCommentId = newDeveloperComments.stream()
                .mapToLong(PullRequestComment::id)
                .max()
                .orElse(trackedPR.lastSeenCommentId());

        workflowTracker.updateLastSeenCommentId(
                trackedPR.owner(), trackedPR.repo(), trackedPR.prNumber(), maxCommentId);
    }
}
