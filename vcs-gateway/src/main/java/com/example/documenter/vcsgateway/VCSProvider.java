package com.example.documenter.vcsgateway;

import com.example.documenter.vcsgateway.domain.PullRequest;
import com.example.documenter.vcsgateway.domain.PullRequestComment;
import com.example.documenter.vcsgateway.domain.PullRequestDiff;
import com.example.documenter.vcsgateway.domain.TreeEntry;

import java.util.List;

/**
 * Abstraction over version-control-system operations.
 *
 * <p>Concrete implementations exist for GitHub, GitLab, and Bitbucket. Provider selection is
 * driven by the {@code vcs.provider} configuration property and resolved at startup by
 * {@code VCSProviderFactory}.
 *
 * <p><strong>Rate-limit handling is provider-owned.</strong> Each implementation catches its
 * provider-specific rate-limit response (HTTP 429) and retries transparently after the
 * indicated delay. Callers never see rate-limit errors.
 *
 * <p>All other API failures are reported as {@link VcsApiException}. Each method's Javadoc
 * documents the expected caller behaviour on error.
 */
public interface VCSProvider {

    /**
     * Fetches the recursive file tree for the given repository and ref.
     *
     * @param owner the repository owner (user or organisation)
     * @param repo  the repository name
     * @param ref   the Git ref (branch, tag, or commit SHA)
     * @return a list of {@link TreeEntry} objects representing every entry in the tree
     * @throws VcsApiException on any non-rate-limit API failure; callers should handle
     *                         the exception (e.g. fail the current operation)
     */
    List<TreeEntry> fetchFileTree(String owner, String repo, String ref);

    /**
     * Fetches the content of a single file from the repository.
     *
     * @param owner the repository owner
     * @param repo  the repository name
     * @param path  the file path relative to the repository root
     * @param ref   the Git ref (branch, tag, or commit SHA)
     * @return the decoded file content as a string
     * @throws VcsApiException on any non-rate-limit API failure; callers are expected to
     *                         catch this per-file and continue processing remaining files
     */
    String fetchFileContent(String owner, String repo, String path, String ref);

    /**
     * Lists all open pull requests (or merge requests) for the given repository.
     *
     * @param owner the repository owner
     * @param repo  the repository name
     * @return a list of {@link PullRequest} objects for every open PR
     * @throws VcsApiException on any non-rate-limit API failure; callers should skip the
     *                         current polling cycle and retry on the next interval
     */
    List<PullRequest> listOpenPullRequests(String owner, String repo);

    /**
     * Fetches the unified diff for a specific pull request.
     *
     * @param owner    the repository owner
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @return a {@link PullRequestDiff} containing per-file diffs
     * @throws VcsApiException on any non-rate-limit API failure
     */
    PullRequestDiff fetchPullRequestDiff(String owner, String repo, int prNumber);

    /**
     * Posts a comment on a pull request.
     *
     * @param owner    the repository owner
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @param comment  the comment to post
     * @throws VcsApiException on any non-rate-limit API failure; callers should log the
     *                         error and continue without aborting the current operation
     */
    void postPullRequestComment(String owner, String repo, int prNumber, PullRequestComment comment);

    /**
     * Lists all comments on a pull request.
     *
     * @param owner    the repository owner
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @return a list of {@link PullRequestComment} objects
     * @throws VcsApiException on any non-rate-limit API failure
     */
    List<PullRequestComment> listPullRequestComments(String owner, String repo, int prNumber);

    /**
     * Approves a pull request.
     *
     * @param owner    the repository owner
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @throws VcsApiException on any non-rate-limit API failure; this method is typically
     *                         invoked as a Temporal activity and will be retried by the
     *                         activity retry policy (initial interval 1s, backoff 2.0,
     *                         max attempts 3)
     */
    void approvePullRequest(String owner, String repo, int prNumber);

    /**
     * Creates a new repository under the given owner.
     *
     * @param owner     the repository owner (user or organisation)
     * @param repoName  the name for the new repository
     * @param isPrivate whether the repository should be private
     * @throws VcsApiException on any non-rate-limit API failure; this exception is
     *                         propagated and will fail the documentation run
     */
    void createRepository(String owner, String repoName, boolean isPrivate);

    /**
     * Pushes (creates or updates) a single file in the repository.
     *
     * <p>If the file already exists, implementations must fetch the current file SHA
     * before updating (required by some VCS APIs such as GitHub).
     *
     * @param owner         the repository owner
     * @param repo          the repository name
     * @param path          the file path relative to the repository root
     * @param content       the file content to push
     * @param commitMessage the commit message
     * @throws VcsApiException on any non-rate-limit API failure; callers should log the
     *                         error and continue processing remaining files
     */
    void pushFile(String owner, String repo, String path, String content, String commitMessage);
}
