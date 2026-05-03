package com.example.documenter.temporalworkflows.workflow;

import com.example.documenter.aigateway.domain.RelevanceScanResult;
import com.example.documenter.aigateway.domain.ReviewDecision;
import com.example.documenter.temporalworkflows.activity.AnalyseDiffActivity;
import com.example.documenter.temporalworkflows.activity.ApprovePRActivity;
import com.example.documenter.temporalworkflows.activity.PostCommentActivity;
import com.example.documenter.temporalworkflows.activity.ScanRelevantDocsActivity;
import com.example.documenter.temporalworkflows.activity.TimeoutActivity;
import com.example.documenter.temporalworkflows.activity.UpdateDocumentationActivity;
import com.example.documenter.temporalworkflows.domain.ConversationTurn;
import com.example.documenter.temporalworkflows.domain.PRReviewState;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of the {@link PRReviewWorkflow} — the autonomous PR review loop.
 *
 * <p>The workflow follows this sequence:</p>
 * <ol>
 *   <li>Scan relevant documentation files via {@link ScanRelevantDocsActivity}</li>
 *   <li>Analyse the diff against relevant docs via {@link AnalyseDiffActivity}</li>
 *   <li>If the AI decides autonomously: update docs and approve the PR</li>
 *   <li>If the AI needs input: post a question, wait for a developer reply signal,
 *       then loop back to step 2 with the updated conversation history</li>
 *   <li>If no reply is received within the timeout: post a timeout comment and complete</li>
 * </ol>
 */
public class PRReviewWorkflowImpl implements PRReviewWorkflow {

    /**
     * Default signal timeout in seconds (24 hours).
     */
    private static final long DEFAULT_SIGNAL_TIMEOUT_SECONDS = 86400L;

    /**
     * Default retry policy for all activities: initial interval 1s, backoff 2.0, max 3 attempts.
     */
    private static final RetryOptions DEFAULT_RETRY_OPTIONS = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)
            .setMaximumAttempts(3)
            .build();

    private static final ActivityOptions DEFAULT_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(DEFAULT_RETRY_OPTIONS)
            .build();

    // Activity stubs — configured with the default retry policy
    private final ScanRelevantDocsActivity scanActivity =
            Workflow.newActivityStub(ScanRelevantDocsActivity.class, DEFAULT_ACTIVITY_OPTIONS);

    private final AnalyseDiffActivity analyseActivity =
            Workflow.newActivityStub(AnalyseDiffActivity.class, DEFAULT_ACTIVITY_OPTIONS);

    private final PostCommentActivity postCommentActivity =
            Workflow.newActivityStub(PostCommentActivity.class, DEFAULT_ACTIVITY_OPTIONS);

    private final UpdateDocumentationActivity updateDocsActivity =
            Workflow.newActivityStub(UpdateDocumentationActivity.class, DEFAULT_ACTIVITY_OPTIONS);

    private final ApprovePRActivity approveActivity =
            Workflow.newActivityStub(ApprovePRActivity.class, DEFAULT_ACTIVITY_OPTIONS);

    private final TimeoutActivity timeoutActivity =
            Workflow.newActivityStub(TimeoutActivity.class, DEFAULT_ACTIVITY_OPTIONS);

    // Mutable workflow state
    private final List<ConversationTurn> history = new ArrayList<>();
    private String pendingReply;
    private boolean replyReceived;

    @Override
    public void developerReplySignal(String commentBody) {
        this.pendingReply = commentBody;
        this.replyReceived = true;
    }

    @Override
    public void start(PRReviewState state) {
        // Step 1: Scan relevant documentation files
        RelevanceScanResult scanResult = scanActivity.scan(
                state.diffPatch(), state.owner(), state.repo());
        List<String> relevantPaths = scanResult != null && scanResult.relevantPaths() != null
                ? scanResult.relevantPaths()
                : List.of();

        // Step 2: Main review loop
        while (true) {
            ReviewDecision decision = analyseActivity.analyse(
                    state.diffPatch(), relevantPaths, history, state.owner(), state.repo());

            // Step 3a: Autonomous — update docs and approve
            if (decision instanceof ReviewDecision.Autonomous autonomous) {
                updateDocsActivity.update(autonomous.updates(), state.owner(), state.repo());
                approveActivity.approve(state.owner(), state.repo(), state.prNumber());
                return;
            }

            // Step 3b: Needs input — post question and wait for reply
            ReviewDecision.NeedsInput needsInput = (ReviewDecision.NeedsInput) decision;
            postCommentActivity.post(state.owner(), state.repo(), state.prNumber(),
                    needsInput.question());

            // Wait for developer reply signal or timeout
            boolean received = Workflow.await(
                    Duration.ofSeconds(DEFAULT_SIGNAL_TIMEOUT_SECONDS),
                    () -> replyReceived);

            if (!received) {
                // Timeout — post follow-up and complete
                timeoutActivity.timeout(state.owner(), state.repo(), state.prNumber(),
                        needsInput.question());
                return;
            }

            // Reply received — append to history and loop
            history.add(new ConversationTurn(needsInput.question(), pendingReply));
            replyReceived = false;
            pendingReply = null;
        }
    }
}
