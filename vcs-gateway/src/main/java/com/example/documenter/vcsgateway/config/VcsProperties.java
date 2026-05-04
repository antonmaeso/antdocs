package com.example.documenter.vcsgateway.config;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Type-safe configuration properties for the VCS provider.
 * Bound from the {@code vcs.*} namespace in application.yml.
 */
@ConfigurationProperties(prefix = "vcs")
@Validated
public class VcsProperties {

    @NotBlank
    private String provider;

    @NotBlank
    private String botUsername;

    @Valid
    private GitHubConfig github = new GitHubConfig();

    @Valid
    private GitLabConfig gitlab = new GitLabConfig();

    @Valid
    private BitbucketConfig bitbucket = new BitbucketConfig();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBotUsername() {
        return botUsername;
    }

    public void setBotUsername(String botUsername) {
        this.botUsername = botUsername;
    }

    public GitHubConfig getGithub() {
        return github;
    }

    public void setGithub(GitHubConfig github) {
        this.github = github;
    }

    public GitLabConfig getGitlab() {
        return gitlab;
    }

    public void setGitlab(GitLabConfig gitlab) {
        this.gitlab = gitlab;
    }

    public BitbucketConfig getBitbucket() {
        return bitbucket;
    }

    public void setBitbucket(BitbucketConfig bitbucket) {
        this.bitbucket = bitbucket;
    }

    /**
     * GitHub-specific configuration (token and API base URL).
     */
    public static class GitHubConfig {

        private String token;
        private String apiBaseUrl = "https://api.github.com";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }
    }

    /**
     * GitLab-specific configuration (token and API base URL).
     */
    public static class GitLabConfig {

        private String token;
        private String apiBaseUrl = "https://gitlab.com/api/v4";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }
    }

    /**
     * Bitbucket-specific configuration (token and API base URL).
     */
    public static class BitbucketConfig {

        private String token;
        private String apiBaseUrl = "https://api.bitbucket.org/2.0";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }
    }
}
