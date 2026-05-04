package com.example.documenter.vcsgateway.domain;

/**
 * Represents an open pull request (or merge request) on a VCS provider.
 *
 * @param number  the PR number
 * @param title   the PR title
 * @param headSha the SHA of the head (source) commit
 * @param baseSha the SHA of the base (target) commit
 */
public record PullRequest(int number, String title, String headSha, String baseSha) {
}
