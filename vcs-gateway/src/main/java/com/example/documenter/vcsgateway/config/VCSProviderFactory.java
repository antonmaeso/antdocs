package com.example.documenter.vcsgateway.config;

import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.bitbucket.BitbucketProvider;
import com.example.documenter.vcsgateway.github.GitHubProvider;
import com.example.documenter.vcsgateway.gitlab.GitLabProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Factory configuration that resolves the active {@link VCSProvider} implementation
 * based on the {@code vcs.provider} property.
 *
 * <p>Performs fail-fast validation at startup:
 * <ul>
 *   <li>The selected provider's PAT must be present and non-blank.</li>
 *   <li>{@code vcs.bot-username} must be present and non-blank.</li>
 *   <li>{@code vcs.provider} must be one of {@code github}, {@code gitlab}, or {@code bitbucket}.</li>
 * </ul>
 */
@Configuration
public class VCSProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(VCSProviderFactory.class);

    private final VcsProperties vcsProperties;

    public VCSProviderFactory(VcsProperties vcsProperties) {
        this.vcsProperties = vcsProperties;
    }

    @Bean
    @Primary
    public VCSProvider vcsProvider() {
        String provider = vcsProperties.getProvider();

        validateBotUsername();
        validateProviderToken(provider);

        log.info("Initialising VCS provider: {}", provider);

        return switch (provider.toLowerCase()) {
            case "github" -> new GitHubProvider(vcsProperties);
            case "gitlab" -> new GitLabProvider(vcsProperties);
            case "bitbucket" -> new BitbucketProvider(vcsProperties);
            default -> throw new IllegalStateException(
                    "Invalid vcs.provider value: '" + provider
                            + "'. Valid values are: github, gitlab, bitbucket");
        };
    }

    private void validateBotUsername() {
        if (vcsProperties.getBotUsername() == null || vcsProperties.getBotUsername().isBlank()) {
            throw new IllegalStateException(
                    "vcs.bot-username must be configured. The bot username is required to filter out "
                            + "the bot's own comments during PR review polling.");
        }
    }

    private void validateProviderToken(String provider) {
        String token = switch (provider.toLowerCase()) {
            case "github" -> vcsProperties.getGithub().getToken();
            case "gitlab" -> vcsProperties.getGitlab().getToken();
            case "bitbucket" -> vcsProperties.getBitbucket().getToken();
            default -> null; // will be caught by the switch in vcsProvider()
        };

        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Personal access token for provider '" + provider
                            + "' is missing. Set vcs." + provider.toLowerCase()
                            + ".token in your configuration.");
        }
    }
}
