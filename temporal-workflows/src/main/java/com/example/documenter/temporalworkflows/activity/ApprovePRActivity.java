package com.example.documenter.temporalworkflows.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity that approves a pull request via the VCS provider.
 */
@ActivityInterface
public interface ApprovePRActivity {

    /**
     * Approves the specified pull request.
     *
     * @param owner    the repository owner
     * @param repo     the repository name
     * @param prNumber the pull request number
     */
    @ActivityMethod
    void approve(String owner, String repo, int prNumber);
}
