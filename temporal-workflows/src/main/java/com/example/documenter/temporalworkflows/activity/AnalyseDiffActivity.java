package com.example.documenter.temporalworkflows.activity;

import com.example.documenter.aigateway.domain.ReviewDecision;
import com.example.documenter.temporalworkflows.domain.ConversationTurn;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * Temporal activity that analyses a PR diff against relevant documentation
 * and decides whether to update docs autonomously or request developer input.
 */
@ActivityInterface
public interface AnalyseDiffActivity {

    /**
     * Fetches the full content of each relevant documentation file, then calls the AI gateway
     * to analyse the diff and produce a review decision.
     *
     * @param diffPatch    the unified diff patch of the pull request
     * @param relevantPaths the paths of documentation files relevant to the diff
     * @param history       the conversation history from previous review turns
     * @param owner         the repository owner
     * @param repo          the repository name
     * @return a {@link ReviewDecision} — either Autonomous or NeedsInput
     */
    @ActivityMethod
    ReviewDecision analyse(String diffPatch, List<String> relevantPaths,
                           List<ConversationTurn> history, String owner, String repo);
}
