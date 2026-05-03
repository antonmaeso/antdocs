package com.example.documenter.temporalworkflows.activity.impl;

import com.example.documenter.temporalworkflows.activity.ApprovePRActivity;
import com.example.documenter.vcsgateway.VCSProvider;

/**
 * Implementation of {@link ApprovePRActivity}.
 *
 * <p>Approves the specified pull request via the VCS provider.</p>
 */
public class ApprovePRActivityImpl implements ApprovePRActivity {

    private final VCSProvider vcsProvider;

    public ApprovePRActivityImpl(VCSProvider vcsProvider) {
        this.vcsProvider = vcsProvider;
    }

    @Override
    public void approve(String owner, String repo, int prNumber) {
        vcsProvider.approvePullRequest(owner, repo, prNumber);
    }
}
