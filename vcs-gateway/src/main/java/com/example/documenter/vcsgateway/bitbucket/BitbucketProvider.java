package com.example.documenter.vcsgateway.bitbucket;

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
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bitbucket implementation of {@link VCSProvider}.
 *
 * <p>Uses Spring's {@link RestClient} to communicate with the Bitbucket REST API (v2.0).
 * All requests carry the {@code Authorization: Bearer {token}} header.
 *
 * <p>Rate-limit handling (HTTP 429) is delegated to {@link RateLimitHandler}.
 * All other non-2xx responses throw {@link VcsApiException}.
 */
public class BitbucketProvider implements VCSProvider {

    private static final Logger log = LoggerFactory.getLogger(BitbucketProvider.class);

    private final RestClient restClient;

    public BitbucketProvider(VcsProperties vcsProperties) {
        VcsProperties.BitbucketConfig bitbucket = vcsProperties.getBitbucket();
        this.restClient = RestClient.builder()
                .baseUrl(bitbucket.getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucket.getToken())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    if (statusCode == 429) {
                        long retryAfterSeconds = parseRetryAfter(response.getHeaders());
                        throw new RateLimitException(
                                "Bitbucket rate limit exceeded for " + request.getURI(),
                                retryAfterSeconds
                        );
                    }
                    throw new VcsApiException(
                            "Bitbucket API error: " + statusCode + " for " + request.getURI(),
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
            List<TreeEntry> allEntries = new ArrayList<>();
            String url = "/repositories/{workspace}/{repo}/src/{ref}/";
            Object[] uriVars = new Object[]{owner, repo, ref};
            boolean useFullUrl = false;
            String nextUrl = null;

            while (true) {
                Map<String, Object> body;
                if (useFullUrl) {
                    body = restClient.get()
                            .uri(nextUrl)
                            .retrieve()
                            .body(Map.class);
                } else {
                    body = restClient.get()
                            .uri(url, uriVars)
                            .retrieve()
                            .body(Map.class);
                }

                if (body == null) {
                    break;
                }

                List<Map<String, Object>> values = (List<Map<String, Object>>) body.get("values");
                if (values != null) {
                    for (Map<String, Object> entry : values) {
                        String path = (String) entry.get("path");
                        String bbType = (String) entry.get("type");
                        String type = mapBitbucketType(bbType);

                        // Extract SHA from commit.hash
                        String sha = null;
                        Map<String, Object> commit = (Map<String, Object>) entry.get("commit");
                        if (commit != null) {
                            sha = (String) commit.get("hash");
                        }

                        allEntries.add(new TreeEntry(path, type, sha));
                    }
                }

                // Bitbucket pagination: follow the "next" URL
                nextUrl = (String) body.get("next");
                if (nextUrl == null) {
                    break;
                }
                useFullUrl = true;
            }

            return allEntries;
        });
    }

    @Override
    public String fetchFileContent(String owner, String repo, String path, String ref) {
        return RateLimitHandler.executeWithRateLimitRetry(() ->
                restClient.get()
                        .uri("/repositories/{workspace}/{repo}/src/{ref}/{path}",
                                owner, repo, ref, path)
                        .retrieve()
                        .body(String.class)
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PullRequest> listOpenPullRequests(String owner, String repo) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            List<PullRequest> pullRequests = new ArrayList<>();
            String url = "/repositories/{workspace}/{repo}/pullrequests?state=OPEN";
            Object[] uriVars = new Object[]{owner, repo};
            boolean useFullUrl = false;
            String nextUrl = null;

            while (true) {
                Map<String, Object> body;
                if (useFullUrl) {
                    body = restClient.get()
                            .uri(nextUrl)
                            .retrieve()
                            .body(Map.class);
                } else {
                    body = restClient.get()
                            .uri(url, uriVars)
                            .retrieve()
                            .body(Map.class);
                }

                if (body == null) {
                    break;
                }

                List<Map<String, Object>> values = (List<Map<String, Object>>) body.get("values");
                if (values != null) {
                    for (Map<String, Object> pr : values) {
                        int id = ((Number) pr.get("id")).intValue();
                        String title = (String) pr.get("title");

                        // source.commit.hash → headSha
                        String headSha = extractNestedCommitHash(pr, "source");

                        // destination.commit.hash → baseSha
                        String baseSha = extractNestedCommitHash(pr, "destination");

                        pullRequests.add(new PullRequest(id, title, headSha, baseSha));
                    }
                }

                nextUrl = (String) body.get("next");
                if (nextUrl == null) {
                    break;
                }
                useFullUrl = true;
            }

            return pullRequests;
        });
    }

    @Override
    public PullRequestDiff fetchPullRequestDiff(String owner, String repo, int prNumber) {
        return RateLimitHandler.executeWithRateLimitRetry(() -> {
            String diffText = restClient.get()
                    .uri("/repositories/{workspace}/{repo}/pullrequests/{id}/diff",
                            owner, repo, prNumber)
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
            List<PullRequestComment> comments = new ArrayList<>();
            String url = "/repositories/{workspace}/{repo}/pullrequests/{id}/comments";
            Object[] uriVars = new Object[]{owner, repo, prNumber};
            boolean useFullUrl = false;
            String nextUrl = null;

            while (true) {
                Map<String, Object> body;
                if (useFullUrl) {
                    body = restClient.get()
                            .uri(nextUrl)
                            .retrieve()
                            .body(Map.class);
                } else {
                    body = restClient.get()
                            .uri(url, uriVars)
                            .retrieve()
                            .body(Map.class);
                }

                if (body == null) {
                    break;
                }

                List<Map<String, Object>> values = (List<Map<String, Object>>) body.get("values");
                if (values != null) {
                    for (Map<String, Object> comment : values) {
                        long id = ((Number) comment.get("id")).longValue();

                        // content.raw → body
                        Map<String, Object> content = (Map<String, Object>) comment.get("content");
                        String commentBody = (content != null) ? (String) content.get("raw") : null;

                        // user.display_name → author
                        Map<String, Object> user = (Map<String, Object>) comment.get("user");
                        String author = (user != null) ? (String) user.get("display_name") : null;

                        // Bitbucket PR comments don't have path/line for general comments
                        comments.add(new PullRequestComment(commentBody, null, 0, author, id));
                    }
                }

                nextUrl = (String) body.get("next");
                if (nextUrl == null) {
                    break;
                }
                useFullUrl = true;
            }

            return comments;
        });
    }

    // ---- Write operations ----

    @Override
    public void postPullRequestComment(String owner, String repo, int prNumber, PullRequestComment comment) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            restClient.post()
                    .uri("/repositories/{workspace}/{repo}/pullrequests/{id}/comments",
                            owner, repo, prNumber)
                    .body(Map.of("content", Map.of("raw", comment.body())))
                    .retrieve()
                    .toBodilessEntity();
        });
    }

    @Override
    public void approvePullRequest(String owner, String repo, int prNumber) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            restClient.post()
                    .uri("/repositories/{workspace}/{repo}/pullrequests/{id}/approve",
                            owner, repo, prNumber)
                    .retrieve()
                    .toBodilessEntity();
        });
    }

    @Override
    public void createRepository(String owner, String repoName, boolean isPrivate) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            try {
                restClient.post()
                        .uri("/repositories/{workspace}/{repo}", owner, repoName)
                        .body(Map.of("scm", "git", "is_private", isPrivate))
                        .retrieve()
                        .toBodilessEntity();
            } catch (VcsApiException e) {
                if (e.getHttpStatus() == 409) {
                    // 409 means the repo already exists — silently succeed (idempotent)
                    log.debug("Repository {}/{} already exists (HTTP 409), treating as success",
                            owner, repoName);
                    return;
                }
                throw e;
            }
        });
    }

    @Override
    public void pushFile(String owner, String repo, String path, String content, String commitMessage) {
        RateLimitHandler.executeWithRateLimitRetry(() -> {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add(path, content);
            formData.add("message", commitMessage);
            formData.add("branch", "main");

            restClient.post()
                    .uri("/repositories/{workspace}/{repo}/src", owner, repo)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .toBodilessEntity();
        });
    }

    // ---- Internal helpers ----

    /**
     * Maps Bitbucket entry types to the canonical types used by {@link TreeEntry}.
     * Bitbucket uses {@code commit_file} for blobs and {@code commit_directory} for trees.
     */
    private static String mapBitbucketType(String bitbucketType) {
        if ("commit_file".equals(bitbucketType)) {
            return "blob";
        }
        if ("commit_directory".equals(bitbucketType)) {
            return "tree";
        }
        return bitbucketType;
    }

    /**
     * Extracts a commit hash from a nested structure like {@code source.commit.hash}
     * or {@code destination.commit.hash}.
     */
    @SuppressWarnings("unchecked")
    private static String extractNestedCommitHash(Map<String, Object> pr, String key) {
        Map<String, Object> branch = (Map<String, Object>) pr.get(key);
        if (branch != null) {
            Map<String, Object> commit = (Map<String, Object>) branch.get("commit");
            if (commit != null) {
                return (String) commit.get("hash");
            }
        }
        return null;
    }

    /**
     * Parses the {@code Retry-After} header (seconds) from a Bitbucket 429 response.
     */
    private static long parseRetryAfter(HttpHeaders headers) {
        String retryAfterHeader = headers.getFirst("Retry-After");
        if (retryAfterHeader != null) {
            try {
                long seconds = Long.parseLong(retryAfterHeader);
                return Math.max(seconds, 1);
            } catch (NumberFormatException e) {
                log.warn("Could not parse Retry-After header: {}", retryAfterHeader);
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
    private static List<FileDiff> parseUnifiedDiff(String diffText) {
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
