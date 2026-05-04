package com.example.documenter.aigateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.ChatClient;

/**
 * Unit tests for {@link SpringAIGateway} startup validation.
 * Verifies that the application fails to start when any required {@code .st} file
 * is absent from the classpath.
 */
@ExtendWith(MockitoExtension.class)
class SpringAIGatewayStartupTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private PromptLoader promptLoader;

    @Test
    void validatePromptTemplates_allPresent_doesNotThrow() {
        when(promptLoader.exists(anyString())).thenReturn(true);

        SpringAIGateway gateway = new SpringAIGateway(chatClient, promptLoader);

        assertDoesNotThrow(gateway::validatePromptTemplates);
        // Verify all 5 templates were checked
        for (String template : SpringAIGateway.REQUIRED_TEMPLATES) {
            verify(promptLoader).exists(template);
        }
    }

    @Test
    void validatePromptTemplates_missingGenerateDocumentation_throwsWithFilename() {
        when(promptLoader.exists(anyString())).thenReturn(true);
        when(promptLoader.exists("generate-documentation.st")).thenReturn(false);

        SpringAIGateway gateway = new SpringAIGateway(chatClient, promptLoader);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                gateway::validatePromptTemplates);
        assertTrue(ex.getMessage().contains("generate-documentation.st"),
                "Exception message should contain the missing filename");
    }

    @Test
    void validatePromptTemplates_missingGenerateReadme_throwsWithFilename() {
        when(promptLoader.exists(anyString())).thenReturn(true);
        when(promptLoader.exists("generate-readme.st")).thenReturn(false);

        SpringAIGateway gateway = new SpringAIGateway(chatClient, promptLoader);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                gateway::validatePromptTemplates);
        assertTrue(ex.getMessage().contains("generate-readme.st"));
    }

    @Test
    void validatePromptTemplates_missingScanRelevantDocs_throwsWithFilename() {
        when(promptLoader.exists(anyString())).thenReturn(true);
        when(promptLoader.exists("scan-relevant-docs.st")).thenReturn(false);

        SpringAIGateway gateway = new SpringAIGateway(chatClient, promptLoader);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                gateway::validatePromptTemplates);
        assertTrue(ex.getMessage().contains("scan-relevant-docs.st"));
    }

    @Test
    void validatePromptTemplates_missingAnalyseDiff_throwsWithFilename() {
        when(promptLoader.exists(anyString())).thenReturn(true);
        when(promptLoader.exists("analyse-diff.st")).thenReturn(false);

        SpringAIGateway gateway = new SpringAIGateway(chatClient, promptLoader);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                gateway::validatePromptTemplates);
        assertTrue(ex.getMessage().contains("analyse-diff.st"));
    }

    @Test
    void validatePromptTemplates_missingPrTimeoutFollowup_throwsWithFilename() {
        when(promptLoader.exists(anyString())).thenReturn(true);
        when(promptLoader.exists("pr-timeout-followup.st")).thenReturn(false);

        SpringAIGateway gateway = new SpringAIGateway(chatClient, promptLoader);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                gateway::validatePromptTemplates);
        assertTrue(ex.getMessage().contains("pr-timeout-followup.st"));
    }
}
