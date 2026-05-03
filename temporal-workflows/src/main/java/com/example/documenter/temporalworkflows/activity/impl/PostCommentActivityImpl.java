package com.example.documenter.temporalworkflows.activity.impl;

import com.example.documenter.temporalworkflows.activity.PostCommentActivity;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.PullRequestComment;

/**
 * Implementation of {@link PostCommentActivity}.
 *
 * <p>Posts a question as a general PR comment via the VCS provider.</p>
 */
public class PostCommentActivityImpl implements PostCommentActivity {

    private final VCSProvider vcsProvider;

    public PostCommentActivityImpl(VCSProvider vcsProvider) {
        this.vcsProvider = vcsProvider;
    }

    @Override
    public void post(String owner, String repo, int prNumber, String question) {
        PullRequestComment comment = new PullRequestComment(question, null, 0, null, 0);
        vcsProvider.postPullRequestComment(owner, repo, prNumber, comment);
    }
}
