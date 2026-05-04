package com.example.documenter.temporalworkflows.activity;

import com.example.documenter.aigateway.domain.RelevanceScanResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity that scans the documentation repository to identify
 * which documentation files are relevant to a given PR diff.
 */
@ActivityInterface
public interface ScanRelevantDocsActivity {

    /**
     * Fetches the documentation file tree, builds summaries, and uses the AI gateway
     * to determine which documentation files are relevant to the diff.
     *
     * @param diffPatch the unified diff patch of the pull request
     * @param owner     the repository owner
     * @param repo      the repository name
     * @return the relevance scan result containing paths of relevant documentation files
     */
    @ActivityMethod
    RelevanceScanResult scan(String diffPatch, String owner, String repo);
}
