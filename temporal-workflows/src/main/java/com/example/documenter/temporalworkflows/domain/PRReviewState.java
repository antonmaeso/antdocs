package com.example.documenter.temporalworkflows.domain;

import java.util.List;

/**
 * The complete state passed to a PR review workflow when it starts.
 *
 * <p>Contains the PR coordinates, the diff to analyse, the list of documentation
 * paths deemed relevant by the scanning step, and the conversation history
 * accumulated across clarification turns.</p>
 *
 * @param owner           the repository owner (user or organisation)
 * @param repo            the repository name
 * @param prNumber        the pull-request number
 * @param diffPatch       the unified diff patch of the pull request
 * @param relevantDocPaths paths to documentation files relevant to the diff
 * @param history         the ordered list of conversation turns so far
 */
public record PRReviewState(
        String owner,
        String repo,
        int prNumber,
        String diffPatch,
        List<String> relevantDocPaths,
        List<ConversationTurn> history
) {
}
