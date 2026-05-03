package com.example.documenter.temporalworkflows.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity that posts a timeout follow-up comment when a developer
 * does not reply within the configured timeout period.
 */
@ActivityInterface
public interface TimeoutActivity {

    /**
     * Posts a timeout follow-up comment on the specified pull request.
     *
     * @param owner        the repository owner
     * @param repo         the repository name
     * @param prNumber     the pull request number
     * @param lastQuestion the last question that was asked before the timeout
     */
    @ActivityMethod
    void timeout(String owner, String repo, int prNumber, String lastQuestion);
}
