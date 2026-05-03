package com.example.documenter.temporalworkflows.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity that posts a question comment on a pull request.
 */
@ActivityInterface
public interface PostCommentActivity {

    /**
     * Posts a comment with the given question text to the specified pull request.
     *
     * @param owner    the repository owner
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @param question the question text to post as a comment
     */
    @ActivityMethod
    void post(String owner, String repo, int prNumber, String question);
}
