package com.example.documenter.vcsgateway.github;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub implementation of {@link VCSProvider}.
 *
 * <p>Uses Spring's {@link RestClient} to communicate with the GitHub REST API.
 * All requests carry {@code Authorization: Bearer {token}} and
 * {@code X-GitHub-Api-Version: 2022-11-28} headers.
 *
 * <p>Rate-limit handling (HTTP 429) is delegated to {@link RateLimitHandler}.
 * All other non-2xx responses throw {@link VcsApiException}.
 */
public class GitHubProvider implements VCSProvider {

    private static final Logger log = LoggerFactory.getLogger(GitHubProvider.class);
    private static final String GITHUB_API_VERSION = "2022-11-28";

    private final RestClient restClient;

    public GitHubProvider(VcsProperties vcsProperties) {
        VcsProperties.GitHubConfig github = vcsProperties.getGithub();
        this.restClient = RestClient.builder()
                .baseUrl(github.getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + github.getToken())
                .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    if (statusCode == 429) {
                        long retryAfterSeconds = parseRateLimitReset(response.getHeaders());
                        throw new RateLimitException(
                                "GitHub rate limit exceeded for " + request.getURI(),
                                retryAfterSeconds
                        );
                    }
                    throw new VcsApiException(
                            "GitHub API error: " + statusCode + " for " + request.getURI(),
                            statusCode
                    );
                })
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<TreeEntry> fetchFileTree(String owner, String repo, String ref) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            Map<String, Object> body = restClient.get()
                    .uri("/repos/{owner}/{repo}/git/trees/{ref}?recursive=1", owner, repo, ref)
                    .retrieve()
                    .body(Map.class);

            if (body == null || !body.containsKey("tree")) {
                return List.of();
            }

            List<Map<String, Object>> tree = (List<Map<String, Object>>) body.get("tree");
            List<TreeEntry> entries = new ArrayList<>(tree.size());
            for (Map<String, Object> entry : tree) {
                entries.add(new TreeEntry(
                        (String) entry.get("path"),
                        (String) entry.get("type"),
                        (String) entry.get("sha")
                ));
            }
            return entries;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public String fetchFileContent(String owner, String repo, String path, String ref) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            Map<String, Object> body = restClient.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}?ref={ref}", owner, repo, path, ref)
                    .retrieve()
                    .body(Map.class);

            if (body == null || !body.containsKey("content")) {
                throw new VcsApiException(
                        "GitHub API returned no content for " + path, 0);
            }

            String encoded = (String) body.get("content");
            // GitHub returns base64 content with newlines — strip them before decoding
            String cleaned = encoded.replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(cleaned));
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PullRequest> listOpenPullRequests(String owner, String repo) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            List<Map<String, Object>> body = restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls?state=open", owner, repo)
                    .retrieve()
                    .body(List.class);

            if (body == null) {
                return List.of();
            }

            List<PullRequest> pullRequests = new ArrayList<>(body.size());
            for (Map<String, Object> pr : body) {
                int number = ((Number) pr.get("number")).intValue();
                String title = (String) pr.get("title");

                Map<String, Object> head = (Map<String, Object>) pr.get("head");
                String headSha = (String) head.get("sha");

                Map<String, Object> base = (Map<String, Object>) pr.get("base");
                String baseSha = (String) base.get("sha");

                pullRequests.add(new PullRequest(number, title, headSha, baseSha));
            }
            return pullRequests;
        });
    }

    @Override
    public PullRequestDiff fetchPullRequestDiff(String owner, String repo, int prNumber) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            String diffText = restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.diff")
                    .retrieve()
                    .body(String.class);

            List<FileDiff> fileDiffs = parseUnifiedDiff(diffText);
            return new PullRequestDiff(prNumber, fileDiffs);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PullRequestComment> listPullRequestComments(String owner, String repo, int prNumber) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            List<Map<String, Object>> body = restClient.get()
                    .uri("/repos/{owner}/{repo}/issues/{prNumber}/comments", owner, repo, prNumber)
                    .retrieve()
                    .body(List.class);

            if (body == null) {
                return List.of();
            }

            List<PullRequestComment> comments = new ArrayList<>(body.size());
            for (Map<String, Object> comment : body) {
                long id = ((Number) comment.get("id")).longValue();
                String commentBody = (String) comment.get("body");

                Map<String, Object> user = (Map<String, Object>) comment.get("user");
                String author = (String) user.get("login");

                // Issue comments don't have path/line — use null/0
                comments.add(new PullRequestComment(commentBody, null, 0, author, id));
            }
            return comments;
        });
    }

    // ---- Write operations ----

    @Override
    public void postPullRequestComment(String owner, String repo, int prNumber, PullRequestComment comment) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            restClient.post()
                    .uri("/repos/{owner}/{repo}/issues/{prNumber}/comments", owner, repo, prNumber)
                    .body(Map.of("body", comment.body()))
                    .retrieve()
                    .toBodilessEntity();
        });
    }

    @Override
    public void approvePullRequest(String owner, String repo, int prNumber) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            restClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{prNumber}/reviews", owner, repo, prNumber)
                    .body(Map.of("event", "APPROVE"))
                    .retrieve()
                    .toBodilessEntity();
        });
    }

    @Override
    public void createRepository(String owner, String repoName, boolean isPrivate) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            Map<String, Object> body = Map.of("name", repoName, "private", isPrivate);
            try {
                // Try creating under an organisation first
                restClient.post()
                        .uri("/orgs/{owner}/repos", owner)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            } catch (VcsApiException e) {
                if (e.getHttpStatus() == 422) {
                    // 422 means the repo already exists — silently succeed (idempotent)
                    log.debug("Repository {}/{} already exists (HTTP 422), treating as success", owner, repoName);
                    return;
                }
                if (e.getHttpStatus() == 404) {
                    // 404 means the owner is a user, not an org — fall back to user endpoint
                    log.debug("Owner {} is not an organisation (HTTP 404), falling back to /user/repos", owner);
                    try {
                        restClient.post()
                                .uri("/user/repos")
                                .body(body)
                                .retrieve()
                                .toBodilessEntity();
                    } catch (VcsApiException fallbackEx) {
                        if (fallbackEx.getHttpStatus() == 422) {
                            // Repo already exists under the user — silently succeed
                            log.debug("Repository {} already exists under user (HTTP 422), treating as success", repoName);
                            return;
                        }
                        throw fallbackEx;
                    }
                    return;
                }
                throw e;
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void pushFile(String owner, String repo, String path, String content, String commitMessage) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            // Step 1: Try to fetch the existing file SHA
            String existingSha = null;
            try {
                Map<String, Object> existing = restClient.get()
                        .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                        .retrieve()
                        .body(Map.class);
                if (existing != null && existing.containsKey("sha")) {
                    existingSha = (String) existing.get("sha");
                }
            } catch (VcsApiException e) {
                if (e.getHttpStatus() != 404) {
                    throw e;
                }
                // 404 means the file doesn't exist yet — that's fine, we'll create it
            }

            // Step 2: Build the request body
            String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
            Map<String, Object> body;
            if (existingSha != null) {
                body = Map.of(
                        "message", commitMessage,
                        "content", encodedContent,
                        "sha", existingSha
                );
            } else {
                body = Map.of(
                        "message", commitMessage,
                        "content", encodedContent
                );
            }

            // Step 3: Create or update the file
            restClient.put()
                    .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        });
    }

    // ---- Internal helpers ----

    /**
     * Parses the {@code X-RateLimit-Reset} header (Unix epoch seconds) and computes
     * the number of seconds to wait before retrying.
     */
    private static long parseRateLimitReset(HttpHeaders headers) {
        String resetHeader = headers.getFirst("X-RateLimit-Reset");
        if (resetHeader != null) {
            try {
                long resetEpoch = Long.parseLong(resetHeader);
                long now = Instant.now().getEpochSecond();
                long retryAfter = resetEpoch - now;
                return Math.max(retryAfter, 1);
            } catch (NumberFormatException e) {
                log.warn("Could not parse X-RateLimit-Reset header: {}", resetHeader);
            }
        }
        // Default to 60 seconds if header is missing or unparseable
        return 60;
    }

    private static final Pattern DIFF_HEADER_PATTERN = Pattern.compile("^diff --git a/.+ b/.+$", Pattern.MULTILINE);
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile("^\\+\\+\\+ b/(.+)$", Pattern.MULTILINE);

    /**
     * Parses a unified diff string into a list of {@link FileDiff} entries.
     *
     * <p>Splits on {@code diff --git} lines, extracts the file path from
     * {@code +++ b/path}, and collects the patch text per file.
     */
    static List<FileDiff> parseUnifiedDiff(String diffText) {
        if (diffText == null || diffText.isBlank()) {
            return List.of();
        }

        List<FileDiff> fileDiffs = new ArrayList<>();

        // Split on "diff --git" boundaries
        String[] sections = DIFF_HEADER_PATTERN.split(diffText);

        // Find all diff headers to pair with sections
        Matcher headerMatcher = DIFF_HEADER_PATTERN.matcher(diffText);
        List<String> headers = new ArrayList<>();
        while (headerMatcher.find()) {
            headers.add(headerMatcher.group());
        }

        // sections[0] is text before the first "diff --git" (usually empty)
        // sections[i] for i>=1 corresponds to headers[i-1]
        for (int i = 0; i < headers.size(); i++) {
            String section = (i + 1 < sections.length) ? sections[i + 1] : "";
            String fullPatch = headers.get(i) + section;

            // Extract file path from +++ b/path
            Matcher pathMatcher = FILE_PATH_PATTERN.matcher(fullPatch);
            String filePath;
            if (pathMatcher.find()) {
                filePath = pathMatcher.group(1);
            } else {
                // Fallback: try to extract from the diff --git header
                String header = headers.get(i);
                int bIndex = header.indexOf(" b/");
                filePath = (bIndex >= 0) ? header.substring(bIndex + 3) : "unknown";
            }

            fileDiffs.add(new FileDiff(filePath, fullPatch));
        }

        return fileDiffs;
    }
}
