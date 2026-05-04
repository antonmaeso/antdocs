package com.example.documenter.temporalworkflows.activity.impl;

import com.example.documenter.temporalworkflows.activity.TimeoutActivity;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.PullRequestComment;

/**
 * Implementation of {@link TimeoutActivity}.
 *
 * <p>Posts a timeout follow-up comment on the pull request when the developer
 * does not reply within the configured timeout period.</p>
 */
public class TimeoutActivityImpl implements TimeoutActivity {

    private final VCSProvider vcsProvider;

    public TimeoutActivityImpl(VCSProvider vcsProvider) {
        this.vcsProvider = vcsProvider;
    }

    @Override
    public void timeout(String owner, String repo, int prNumber, String lastQuestion) {
        String timeoutMessage = String.format(
                "⏰ No response was received within the configured timeout period. "
                + "The last question asked was:\n\n> %s\n\n"
                + "This review has been closed. If you'd like to continue, please open a new PR or re-open this one.",
                lastQuestion);
        PullRequestComment comment = new PullRequestComment(timeoutMessage, null, 0, null, 0);
        vcsProvider.postPullRequestComment(owner, repo, prNumber, comment);
    }
}
