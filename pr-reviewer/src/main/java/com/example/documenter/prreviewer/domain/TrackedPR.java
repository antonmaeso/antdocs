package com.example.documenter.prreviewer.domain;

/**
 * Represents a pull request that is currently being tracked by the PR reviewer.
 *
 * <p>Each tracked PR is associated with a Temporal workflow and records the ID of
 * the last comment seen, so that only new developer replies trigger signals.</p>
 *
 * @param owner             the repository owner (user or organisation)
 * @param repo              the repository name
 * @param prNumber          the pull-request number
 * @param workflowId        the Temporal workflow ID managing this PR's review
 * @param lastSeenCommentId the ID of the most recently processed comment
 */
public record TrackedPR(String owner, String repo, int prNumber, String workflowId, long lastSeenCommentId) {
}
