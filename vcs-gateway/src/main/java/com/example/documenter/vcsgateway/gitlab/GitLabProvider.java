package com.example.documenter.vcsgateway.gitlab;

import com.example.documenter.vcsgateway.RateLimitException;
import com.example.documenter.vcsgateway.RateLimitHandler;
import com.example.documenter.vcsgateway.VcsApiException;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.config.VcsProperties;
import com.example.documenter.vcsgateway.domain.FileDiff;
import com.example.documenter.vcsgateway.domain.PullRequest;
import com.example.documenter.vcsgateway.domain.PullRequestComment;
import com.example.documenter.vcsgateway.domain.PullRequestDiff;
import com.example.documenter.vcsgateway.domain.TreeEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitLab implementation of {@link VCSProvider}.
 *
 * <p>Uses Spring's {@link RestClient} to communicate with the GitLab REST API (v4).
 * All requests carry the {@code PRIVATE-TOKEN: {token}} header.
 *
 * <p>Rate-limit handling (HTTP 429) is delegated to {@link RateLimitHandler}.
 * All other non-2xx responses throw {@link VcsApiException}.
 */
public class GitLabProvider implements VCSProvider {

    private static final Logger log = LoggerFactory.getLogger(GitLabProvider.class);

    private final RestClient restClient;

    public GitLabProvider(VcsProperties vcsProperties) {
        VcsProperties.GitLabConfig gitlab = vcsProperties.getGitlab();
        this.restClient = RestClient.builder()
                .baseUrl(gitlab.getApiBaseUrl())
                .defaultHeader("PRIVATE-TOKEN", gitlab.getToken())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    if (statusCode == 429) {
                        long retryAfterSeconds = parseRateLimitReset(response.getHeaders());
                        throw new RateLimitException(
                                "GitLab rate limit exceeded for " + request.getURI(),
                                retryAfterSeconds
                        );
                    }
                    throw new VcsApiException(
                            "GitLab API error: " + statusCode + " for " + request.getURI(),
                            statusCode
                    );
                })
                .build();
    }

    // ---- Read operations ----

    @Override
    @SuppressWarnings("unchecked")
    public List<TreeEntry> fetchFileTree(String owner, String repo, String ref) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            String projectId = encodeProjectId(owner, repo);
            List<TreeEntry> allEntries = new ArrayList<>();
            int page = 1;

            while (true) {
                List<Map<String, Object>> body = restClient.get()
                        .uri("/projects/{projectId}/repository/tree?recursive=true&ref={ref}&per_page=100&page={page}",
                                projectId, ref, page)
                        .retrieve()
                        .body(List.class);

                if (body == null || body.isEmpty()) {
                    break;
                }

                for (Map<String, Object> entry : body) {
                    allEntries.add(new TreeEntry(
                            (String) entry.get("path"),
                            (String) entry.get("type"),
                            (String) entry.get("id")
                    ));
                }

                if (body.size() < 100) {
                    break;
                }
                page++;
            }

            return allEntries;
        });
    }

    @Override
    public String fetchFileContent(String owner, String repo, String path, String ref) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            String projectId = encodeProjectId(owner, repo);
            String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);

            return restClient.get()
                    .uri("/projects/{projectId}/repository/files/{encodedPath}/raw?ref={ref}",
                            projectId, encodedPath, ref)
                    .retrieve()
                    .body(String.class);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PullRequest> listOpenPullRequests(String owner, String repo) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            String projectId = encodeProjectId(owner, repo);

            List<Map<String, Object>> body = restClient.get()
                    .uri("/projects/{projectId}/merge_requests?state=opened", projectId)
                    .retrieve()
                    .body(List.class);

            if (body == null) {
                return List.of();
            }

            List<PullRequest> pullRequests = new ArrayList<>(body.size());
            for (Map<String, Object> mr : body) {
                int iid = ((Number) mr.get("iid")).intValue();
                String title = (String) mr.get("title");

                // Extract head SHA: prefer diff_refs.head_sha, fall back to sha field
                String headSha = extractHeadSha(mr);

                // Extract base SHA from diff_refs.base_sha
                String baseSha = extractBaseSha(mr);

                pullRequests.add(new PullRequest(iid, title, headSha, baseSha));
            }
            return pullRequests;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public PullRequestDiff fetchPullRequestDiff(String owner, String repo, int prNumber) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            String projectId = encodeProjectId(owner, repo);

            List<Map<String, Object>> body = restClient.get()
                    .uri("/projects/{projectId}/merge_requests/{iid}/diffs", projectId, prNumber)
                    .retrieve()
                    .body(List.class);

            if (body == null) {
                return new PullRequestDiff(prNumber, List.of());
            }

            List<FileDiff> fileDiffs = new ArrayList<>(body.size());
            for (Map<String, Object> entry : body) {
                String newPath = (String) entry.get("new_path");
                String diff = (String) entry.get("diff");
                fileDiffs.add(new FileDiff(newPath, diff));
            }
            return new PullRequestDiff(prNumber, fileDiffs);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PullRequestComment> listPullRequestComments(String owner, String repo, int prNumber) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            String projectId = encodeProjectId(owner, repo);

            List<Map<String, Object>> body = restClient.get()
                    .uri("/projects/{projectId}/merge_requests/{iid}/notes", projectId, prNumber)
                    .retrieve()
                    .body(List.class);

            if (body == null) {
                return List.of();
            }

            List<PullRequestComment> comments = new ArrayList<>(body.size());
            for (Map<String, Object> note : body) {
                long id = ((Number) note.get("id")).longValue();
                String noteBody = (String) note.get("body");

                Map<String, Object> author = (Map<String, Object>) note.get("author");
                String username = (String) author.get("username");

                // GitLab notes don't have path/line for general MR notes — use null/0
                comments.add(new PullRequestComment(noteBody, null, 0, username, id));
            }
            return comments;
        });
    }

    // ---- Write operations ----

    @Override
    public void postPullRequestComment(String owner, String repo, int prNumber, PullRequestComment comment) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            String projectId = encodeProjectId(owner, repo);
            restClient.post()
                    .uri("/projects/{projectId}/merge_requests/{iid}/notes", projectId, prNumber)
                    .body(Map.of("body", comment.body()))
                    .retrieve()
                    .toBodilessEntity();
        });
    }

    @Override
    public void approvePullRequest(String owner, String repo, int prNumber) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            String projectId = encodeProjectId(owner, repo);
            restClient.post()
                    .uri("/projects/{projectId}/merge_requests/{iid}/approve", projectId, prNumber)
                    .retrieve()
                    .toBodilessEntity();
        });
    }

    @Override
    public void createRepository(String owner, String repoName, boolean isPrivate) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            try {
                restClient.post()
                        .uri("/projects")
                        .body(Map.of(
                                "name", repoName,
                                "namespace_id", owner,
                                "visibility", isPrivate ? "private" : "public"
                        ))
                        .retrieve()
                        .toBodilessEntity();
            } catch (VcsApiException e) {
                if (e.getHttpStatus() == 409) {
                    // 409 means the project already exists — silently succeed (idempotent)
                    log.debug("Repository {}/{} already exists (HTTP 409), treating as success", owner, repoName);
                    return;
                }
                throw e;
            }
        });
    }

    @Override
    public void pushFile(String owner, String repo, String path, String content, String commitMessage) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            String projectId = encodeProjectId(owner, repo);
            String urlEncodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);

            // Step 1: Check if the file already exists
            boolean fileExists = false;
            try {
                restClient.get()
                        .uri("/projects/{projectId}/repository/files/{path}?ref=main",
                                projectId, urlEncodedPath)
                        .retrieve()
                        .toBodilessEntity();
                fileExists = true;
            } catch (VcsApiException e) {
                if (e.getHttpStatus() != 404) {
                    throw e;
                }
                // 404 means the file doesn't exist yet — we'll create it
            }

            // Step 2: Create or update the file
            Map<String, String> body = Map.of(
                    "branch", "main",
                    "content", content,
                    "commit_message", commitMessage
            );

            if (fileExists) {
                restClient.put()
                        .uri("/projects/{projectId}/repository/files/{path}",
                                projectId, urlEncodedPath)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            } else {
                restClient.post()
                        .uri("/projects/{projectId}/repository/files/{path}",
                                projectId, urlEncodedPath)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            }
        });
    }

    // ---- Internal helpers ----

    /**
     * Encodes the GitLab project ID as URL-encoded {@code owner/repo}.
     */
    private static String encodeProjectId(String owner, String repo) {
        return URLEncoder.encode(owner + "/" + repo, StandardCharsets.UTF_8);
    }

    /**
     * Extracts the head SHA from a merge request map.
     * Prefers {@code diff_refs.head_sha}, falls back to {@code sha}.
     */
    @SuppressWarnings("unchecked")
    private static String extractHeadSha(Map<String, Object> mr) {
        Map<String, Object> diffRefs = (Map<String, Object>) mr.get("diff_refs");
        if (diffRefs != null && diffRefs.get("head_sha") != null) {
            return (String) diffRefs.get("head_sha");
        }
        // Fall back to sha from head_pipeline or top-level sha
        Map<String, Object> headPipeline = (Map<String, Object>) mr.get("head_pipeline");
        if (headPipeline != null && headPipeline.get("sha") != null) {
            return (String) headPipeline.get("sha");
        }
        return (String) mr.get("sha");
    }

    /**
     * Extracts the base SHA from a merge request map via {@code diff_refs.base_sha}.
     */
    @SuppressWarnings("unchecked")
    private static String extractBaseSha(Map<String, Object> mr) {
        Map<String, Object> diffRefs = (Map<String, Object>) mr.get("diff_refs");
        if (diffRefs != null && diffRefs.get("base_sha") != null) {
            return (String) diffRefs.get("base_sha");
        }
        return null;
    }

    /**
     * Parses the {@code RateLimit-Reset} header (Unix epoch seconds) and computes
     * the number of seconds to wait before retrying.
     */
    private static long parseRateLimitReset(HttpHeaders headers) {
        String resetHeader = headers.getFirst("RateLimit-Reset");
        if (resetHeader != null) {
            try {
                long resetEpoch = Long.parseLong(resetHeader);
                long now = Instant.now().getEpochSecond();
                long retryAfter = resetEpoch - now;
                return Math.max(retryAfter, 1);
            } catch (NumberFormatException e) {
                log.warn("Could not parse RateLimit-Reset header: {}", resetHeader);
            }
        }
        // Default to 60 seconds if header is missing or unparseable
        return 60;
    }
}
