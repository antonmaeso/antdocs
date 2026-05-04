package com.example.documenter.vcsgateway.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.bitbucket.BitbucketProvider;
import com.example.documenter.vcsgateway.github.GitHubProvider;
import com.example.documenter.vcsgateway.gitlab.GitLabProvider;

/**
 * Unit tests for {@link VCSProviderFactory}.
 *
 * <p>Tests verify that the factory creates the correct provider implementation for each
 * valid {@code vcs.provider} value, and fails fast with descriptive errors for invalid
 * configurations (unknown provider, missing PAT, missing bot-username).
 */
class VCSProviderFactoryTest {

    // ---- Valid provider selection ----

    @Test
    void vcsProvider_github_returnsGitHubProvider() {
        VcsProperties props = validPropsFor("github");
        props.getGithub().setToken("ghp_test-token");

        VCSProviderFactory factory = new VCSProviderFactory(props);
        VCSProvider provider = factory.vcsProvider();

        assertInstanceOf(GitHubProvider.class, provider);
    }

    @Test
    void vcsProvider_gitlab_returnsGitLabProvider() {
        VcsProperties props = validPropsFor("gitlab");
        props.getGitlab().setToken("glpat-test-token");

        VCSProviderFactory factory = new VCSProviderFactory(props);
        VCSProvider provider = factory.vcsProvider();

        assertInstanceOf(GitLabProvider.class, provider);
    }

    @Test
    void vcsProvider_bitbucket_returnsBitbucketProvider() {
        VcsProperties props = validPropsFor("bitbucket");
        props.getBitbucket().setToken("bb-test-token");

        VCSProviderFactory factory = new VCSProviderFactory(props);
        VCSProvider provider = factory.vcsProvider();

        assertInstanceOf(BitbucketProvider.class, provider);
    }

    // ---- Invalid provider ----

    @Test
    void vcsProvider_invalidProvider_throwsIllegalStateException() {
        // The factory validates the token first via validateProviderToken(), which uses
        // a switch with default -> null for unknown providers. This means an unknown
        // provider like "svn" will fail at token validation (null token) before reaching
        // the provider switch. The exception is still IllegalStateException with a
        // descriptive message about the missing token for the invalid provider.
        VcsProperties props = validPropsFor("svn");

        VCSProviderFactory factory = new VCSProviderFactory(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, factory::vcsProvider);
        assertTrue(ex.getMessage().contains("svn"),
                "Expected message to reference the invalid provider 'svn' but was: " + ex.getMessage());
    }

    // ---- Missing PAT ----

    @Test
    void vcsProvider_missingGithubToken_throwsIllegalStateException() {
        VcsProperties props = validPropsFor("github");
        props.getGithub().setToken("  ");

        VCSProviderFactory factory = new VCSProviderFactory(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, factory::vcsProvider);
        assertTrue(ex.getMessage().toLowerCase().contains("token"),
                "Expected message to mention 'token' but was: " + ex.getMessage());
    }

    @Test
    void vcsProvider_missingGitlabToken_throwsIllegalStateException() {
        VcsProperties props = validPropsFor("gitlab");
        props.getGitlab().setToken("  ");

        VCSProviderFactory factory = new VCSProviderFactory(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, factory::vcsProvider);
        assertTrue(ex.getMessage().toLowerCase().contains("token"),
                "Expected message to mention 'token' but was: " + ex.getMessage());
    }

    @Test
    void vcsProvider_missingBitbucketToken_throwsIllegalStateException() {
        VcsProperties props = validPropsFor("bitbucket");
        props.getBitbucket().setToken("  ");

        VCSProviderFactory factory = new VCSProviderFactory(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, factory::vcsProvider);
        assertTrue(ex.getMessage().toLowerCase().contains("token"),
                "Expected message to mention 'token' but was: " + ex.getMessage());
    }

    // ---- Missing bot-username ----

    @Test
    void vcsProvider_missingBotUsername_throwsIllegalStateException() {
        VcsProperties props = validPropsFor("github");
        props.getGithub().setToken("ghp_test-token");
        props.setBotUsername("  ");

        VCSProviderFactory factory = new VCSProviderFactory(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, factory::vcsProvider);
        assertTrue(ex.getMessage().toLowerCase().contains("bot-username"),
                "Expected message to mention 'bot-username' but was: " + ex.getMessage());
    }

    // ---- Helper ----

    /**
     * Creates a {@link VcsProperties} instance with the given provider, a valid bot-username,
     * and valid tokens for all providers. Individual tests override the specific field they
     * want to test.
     */
    private static VcsProperties validPropsFor(String provider) {
        VcsProperties props = new VcsProperties();
        props.setProvider(provider);
        props.setBotUsername("test-bot");

        VcsProperties.GitHubConfig github = new VcsProperties.GitHubConfig();
        github.setToken("ghp_default-token");
        props.setGithub(github);

        VcsProperties.GitLabConfig gitlab = new VcsProperties.GitLabConfig();
        gitlab.setToken("glpat-default-token");
        props.setGitlab(gitlab);

        VcsProperties.BitbucketConfig bitbucket = new VcsProperties.BitbucketConfig();
        bitbucket.setToken("bb-default-token");
        props.setBitbucket(bitbucket);

        return props;
    }
}
