package com.example.documenter.temporalworkflows.workflow;

import com.example.documenter.temporalworkflows.domain.PRReviewState;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal workflow interface for the autonomous PR review loop.
 *
 * <p>The workflow scans relevant documentation, analyses the PR diff, and either
 * updates documentation autonomously or conducts a multi-turn conversation with
 * the developer until it has enough information to do so.</p>
 */
@WorkflowInterface
public interface PRReviewWorkflow {

    /**
     * Starts the PR review workflow with the given initial state.
     *
     * @param state the PR review state containing PR coordinates, diff, and initial context
     */
    @WorkflowMethod
    void start(PRReviewState state);

    /**
     * Signal method invoked when a developer replies to a question posted by the workflow.
     *
     * @param commentBody the body text of the developer's reply comment
     */
    @SignalMethod
    void developerReplySignal(String commentBody);
}
