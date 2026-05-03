package com.example.documenter.vcsgateway.domain;

import java.util.List;

/**
 * Represents the complete diff for a pull request, containing all file-level diffs.
 *
 * @param prNumber  the pull request number
 * @param fileDiffs the list of per-file diffs in this pull request
 */
public record PullRequestDiff(int prNumber, List<FileDiff> fileDiffs) {
}
