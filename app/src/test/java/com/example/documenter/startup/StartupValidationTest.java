package com.example.documenter.startup;

import com.example.documenter.DocumenterApplication;
import com.example.testonly.MissingPromptTemplateTestApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupValidationTest {

    @Nested
    @DisplayName("Invalid VCS provider value")
    class InvalidProvider {
        @Test
        void invalidProviderValue() {
            assertStartupFails(
                new String[]{"--vcs.provider=invalid-provider", "--vcs.bot-username=test-bot", "--vcs.github.token=dummy-token"},
                "invalid-provider"
            );
        }
    }

    @Nested
    @DisplayName("Missing PAT for selected provider")
    class MissingPAT {
        @Test
        void missingGitHubToken() {
            assertStartupFails(
                new String[]{"--vcs.provider=github", "--vcs.bot-username=test-bot", "--vcs.github.token="},
                "Personal access token for provider 'github' is missing"
            );
        }

        @Test
        void missingGitLabToken() {
            assertStartupFails(
                new String[]{"--vcs.provider=gitlab", "--vcs.bot-username=test-bot", "--vcs.gitlab.token="},
                "Personal access token for provider 'gitlab' is missing"
            );
        }

        @Test
        void missingBitbucketToken() {
            assertStartupFails(
                new String[]{"--vcs.provider=bitbucket", "--vcs.bot-username=test-bot", "--vcs.bitbucket.token="},
                "Personal access token for provider 'bitbucket' is missing"
            );
        }
    }

    @Nested
    @DisplayName("Missing bot-username")
    class MissingBotUsername {
        @Test
        void missingBotUsername() {
            SpringApplication app = new SpringApplication(DocumenterApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            assertThatThrownBy(() -> { ConfigurableApplicationContext ctx = app.run(validBaseArgs("--vcs.provider=github", "--vcs.bot-username=", "--vcs.github.token=dummy-token")); ctx.close(); }).satisfies(ex -> { String msg = chainMessage(ex); assertThat(msg).satisfiesAnyOf(m -> assertThat(m).contains("bot-username"), m -> assertThat(m).contains("botUsername")); });
        }
    }

    @Nested
    @DisplayName("Missing AI configuration")
    class MissingAIConfig {
        @Test
        void missingAIBaseUrl() {
            SpringApplication app = new SpringApplication(DocumenterApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            assertThatThrownBy(() -> { ConfigurableApplicationContext ctx = app.run("--vcs.provider=github", "--vcs.bot-username=test-bot", "--vcs.github.token=dummy-token", "--spring.ai.ollama.base-url=", "--spring.ai.ollama.chat.model=llama3", "--temporal.host=localhost", "--temporal.port=7233", "--temporal.namespace=default", "--polling.interval-seconds=60", "--target-repository.owner=test", "--target-repository.repo=test", "--target-repository.ref=main"); ctx.close(); }).satisfies(ex -> { String msg = chainMessage(ex); assertThat(msg).satisfiesAnyOf(m -> assertThat(m).contains("base-url"), m -> assertThat(m).contains("baseUrl"), m -> assertThat(m).contains("AI configuration")); });
        }

        @Test
        void missingAIChatModel() {
            SpringApplication app = new SpringApplication(DocumenterApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            assertThatThrownBy(() -> { ConfigurableApplicationContext ctx = app.run("--vcs.provider=github", "--vcs.bot-username=test-bot", "--vcs.github.token=dummy-token", "--spring.ai.ollama.base-url=http://localhost:11434", "--spring.ai.ollama.chat.model=", "--temporal.host=localhost", "--temporal.port=7233", "--temporal.namespace=default", "--polling.interval-seconds=60", "--target-repository.owner=test", "--target-repository.repo=test", "--target-repository.ref=main"); ctx.close(); }).satisfies(ex -> { String msg = chainMessage(ex); assertThat(msg).satisfiesAnyOf(m -> assertThat(m).contains("chat.model"), m -> assertThat(m).contains("model"), m -> assertThat(m).contains("AI configuration")); });
        }
    }

    @Nested
    @DisplayName("Missing prompt template .st file")
    class MissingPromptTemplate {
        @Test
        void missingPromptTemplateFile() {
            SpringApplication app = new SpringApplication(MissingPromptTemplateTestApp.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            assertThatThrownBy(() -> { ConfigurableApplicationContext ctx = app.run("--spring.ai.ollama.base-url=http://localhost:11434", "--spring.ai.ollama.chat.model=llama3"); ctx.close(); }).satisfies(ex -> { String msg = chainMessage(ex); assertThat(msg).contains("prompt template file missing from classpath"); });
        }
    }

    private void assertStartupFails(String[] vcsOverrides, String expectedMessage) {
        SpringApplication app = new SpringApplication(DocumenterApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> { ConfigurableApplicationContext ctx = app.run(validBaseArgs(vcsOverrides)); ctx.close(); }).satisfies(ex -> assertThat(chainMessage(ex)).contains(expectedMessage));
    }

    private static String[] validBaseArgs(String... overrides) {
        String[] base = {"--spring.ai.ollama.base-url=http://localhost:11434", "--spring.ai.ollama.chat.model=llama3", "--temporal.host=localhost", "--temporal.port=7233", "--temporal.namespace=default", "--polling.interval-seconds=60", "--target-repository.owner=test", "--target-repository.repo=test", "--target-repository.ref=main"};
        String[] result = new String[overrides.length + base.length];
        System.arraycopy(overrides, 0, result, 0, overrides.length);
        System.arraycopy(base, 0, result, overrides.length, base.length);
        return result;
    }

    private static String chainMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        while (current != null) { if (current.getMessage() != null) { sb.append(current.getMessage()).append(" | "); } current = current.getCause(); }
        return sb.toString();
    }
}
