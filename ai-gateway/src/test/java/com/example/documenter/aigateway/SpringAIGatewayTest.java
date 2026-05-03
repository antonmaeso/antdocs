package com.example.documenter.aigateway;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.example.documenter.aigateway.domain.ConversationTurn;
import com.example.documenter.aigateway.domain.DocFileContent;
import com.example.documenter.aigateway.domain.DocFileSummary;
import com.example.documenter.aigateway.domain.RelevanceScanResult;
import com.example.documenter.aigateway.domain.ReviewDecision;

/**
 * Unit tests for {@link SpringAIGateway}.
 * Verifies each method loads the correct template, passes the correct variables,
 * and correctly parses AI responses.
 */
@ExtendWith(MockitoExtension.class)
class SpringAIGatewayTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private PromptLoader promptLoader;

    private SpringAIGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new SpringAIGateway(chatClient, promptLoader);
    }

    private void mockAIResponse(String responseText) {
        Generation generation = new Generation(responseText);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        when(chatClient.call(any(Prompt.class))).thenReturn(chatResponse);
    }

    // ---- generateDocumentation ----

    @Nested
    class GenerateDocumentation {

        @Test
        void loadsCorrectTemplateWithCorrectVariables() {
            when(promptLoader.load(eq("generate-documentation.st"), any())).thenReturn("rendered prompt");
            mockAIResponse("# Documentation\nSome docs");

            gateway.generateDocumentation("public class Foo {}", "src/Foo.java");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(promptLoader).load(eq("generate-documentation.st"), varsCaptor.capture());

            Map<String, String> vars = varsCaptor.getValue();
            assertEquals("src/Foo.java", vars.get("filePath"));
            assertEquals("public class Foo {}", vars.get("fileContent"));
        }

        @Test
        void callsChatClientWithRenderedPrompt() {
            when(promptLoader.load(eq("generate-documentation.st"), any())).thenReturn("rendered prompt");
            mockAIResponse("# Documentation");

            gateway.generateDocumentation("content", "path");

            ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
            verify(chatClient).call(promptCaptor.capture());
            assertTrue(promptCaptor.getValue().getContents().contains("rendered prompt"));
        }

        @Test
        void returnsAIResponseContent() {
            when(promptLoader.load(eq("generate-documentation.st"), any())).thenReturn("rendered");
            mockAIResponse("# Generated Docs\nThis is the documentation.");

            String result = gateway.generateDocumentation("content", "path");

            assertEquals("# Generated Docs\nThis is the documentation.", result);
        }
    }

    // ---- generateRepositoryReadme ----

    @Nested
    class GenerateRepositoryReadme {

        @Test
        void loadsCorrectTemplateWithCorrectVariables() {
            when(promptLoader.load(eq("generate-readme.st"), any())).thenReturn("rendered readme");
            mockAIResponse("# README");

            gateway.generateRepositoryReadme(List.of("File A: does X", "File B: does Y"), "my-repo");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(promptLoader).load(eq("generate-readme.st"), varsCaptor.capture());

            Map<String, String> vars = varsCaptor.getValue();
            assertEquals("my-repo", vars.get("repoName"));
            assertEquals("File A: does X\nFile B: does Y", vars.get("fileSummaries"));
        }

        @Test
        void returnsAIResponseContent() {
            when(promptLoader.load(eq("generate-readme.st"), any())).thenReturn("rendered");
            mockAIResponse("# my-repo\nOverview of the repo.");

            String result = gateway.generateRepositoryReadme(List.of("summary1"), "my-repo");

            assertEquals("# my-repo\nOverview of the repo.", result);
        }
    }

    // ---- scanRelevantDocs ----

    @Nested
    class ScanRelevantDocs {

        @Test
        void loadsCorrectTemplateWithCorrectVariables() {
            when(promptLoader.load(eq("scan-relevant-docs.st"), any())).thenReturn("rendered scan");
            mockAIResponse("[\"docs/api.md\"]");

            List<DocFileSummary> docs = List.of(
                    new DocFileSummary("docs/api.md", "API reference"),
                    new DocFileSummary("docs/guide.md", "User guide")
            );
            gateway.scanRelevantDocs("diff content", docs);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(promptLoader).load(eq("scan-relevant-docs.st"), varsCaptor.capture());

            Map<String, String> vars = varsCaptor.getValue();
            assertEquals("diff content", vars.get("diffPatch"));
            assertEquals("docs/api.md: API reference\ndocs/guide.md: User guide", vars.get("docSummaries"));
        }

        @Test
        void returnsRelevanceScanResult() {
            when(promptLoader.load(eq("scan-relevant-docs.st"), any())).thenReturn("rendered");
            mockAIResponse("[\"docs/api.md\", \"docs/guide.md\"]");

            RelevanceScanResult result = gateway.scanRelevantDocs("diff",
                    List.of(new DocFileSummary("docs/api.md", "API")));

            assertEquals(List.of("docs/api.md", "docs/guide.md"), result.relevantPaths());
        }
    }

    // ---- analyseAndDecide ----

    @Nested
    class AnalyseAndDecide {

        @Test
        void loadsCorrectTemplateWithCorrectVariables() {
            when(promptLoader.load(eq("analyse-diff.st"), any())).thenReturn("rendered analyse");
            mockAIResponse("QUESTION: What does this change do?");

            List<DocFileContent> docs = List.of(
                    new DocFileContent("docs/api.md", "# API\nContent here")
            );
            List<ConversationTurn> history = List.of(
                    new ConversationTurn("Previous question?", "Previous answer.")
            );

            gateway.analyseAndDecide("diff patch", docs, history);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(promptLoader).load(eq("analyse-diff.st"), varsCaptor.capture());

            Map<String, String> vars = varsCaptor.getValue();
            assertEquals("diff patch", vars.get("diffPatch"));
            assertTrue(vars.get("relevantDocs").contains("--- docs/api.md ---"));
            assertTrue(vars.get("relevantDocs").contains("# API\nContent here"));
            assertTrue(vars.get("history").contains("Q: Previous question?"));
            assertTrue(vars.get("history").contains("A: Previous answer."));
        }

        @Test
        void returnsReviewDecisionForAutonomous() {
            when(promptLoader.load(eq("analyse-diff.st"), any())).thenReturn("rendered");
            mockAIResponse("AUTONOMOUS: [{\"path\":\"docs/api.md\",\"newContent\":\"Updated content\"}]");

            ReviewDecision result = gateway.analyseAndDecide("diff",
                    List.of(new DocFileContent("docs/api.md", "old")), List.of());

            assertInstanceOf(ReviewDecision.Autonomous.class, result);
            ReviewDecision.Autonomous autonomous = (ReviewDecision.Autonomous) result;
            assertEquals(1, autonomous.updates().size());
            assertEquals("docs/api.md", autonomous.updates().get(0).path());
            assertEquals("Updated content", autonomous.updates().get(0).newContent());
        }

        @Test
        void returnsReviewDecisionForNeedsInput() {
            when(promptLoader.load(eq("analyse-diff.st"), any())).thenReturn("rendered");
            mockAIResponse("QUESTION: Can you explain the intent of this change?");

            ReviewDecision result = gateway.analyseAndDecide("diff",
                    List.of(new DocFileContent("docs/api.md", "old")), List.of());

            assertInstanceOf(ReviewDecision.NeedsInput.class, result);
            assertEquals("Can you explain the intent of this change?",
                    ((ReviewDecision.NeedsInput) result).question());
        }
    }

    // ---- generateUpdatedDocContent ----

    @Nested
    class GenerateUpdatedDocContent {

        @Test
        void loadsCorrectTemplateWithCorrectVariables() {
            when(promptLoader.load(eq("analyse-diff.st"), any())).thenReturn("rendered update");
            mockAIResponse("# Updated Doc");

            List<ConversationTurn> history = List.of(
                    new ConversationTurn("Q1?", "A1.")
            );

            gateway.generateUpdatedDocContent("original markdown", "diff patch", history);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(promptLoader).load(eq("analyse-diff.st"), varsCaptor.capture());

            Map<String, String> vars = varsCaptor.getValue();
            assertEquals("diff patch", vars.get("diffPatch"));
            assertEquals("original markdown", vars.get("relevantDocs"));
            assertTrue(vars.get("history").contains("Q: Q1?"));
            assertTrue(vars.get("history").contains("A: A1."));
        }

        @Test
        void returnsAIResponseContent() {
            when(promptLoader.load(eq("analyse-diff.st"), any())).thenReturn("rendered");
            mockAIResponse("# Updated Documentation\nNew content here.");

            String result = gateway.generateUpdatedDocContent("original", "diff", List.of());

            assertEquals("# Updated Documentation\nNew content here.", result);
        }
    }

    // ---- parseJsonArrayOfStrings ----

    @Nested
    class ParseJsonArrayOfStrings {

        @Test
        void parsesValidJsonArray() {
            List<String> result = gateway.parseJsonArrayOfStrings("[\"file1.md\", \"file2.md\"]");
            assertEquals(List.of("file1.md", "file2.md"), result);
        }

        @Test
        void parsesEmptyArray() {
            List<String> result = gateway.parseJsonArrayOfStrings("[]");
            assertTrue(result.isEmpty());
        }

        @Test
        void parsesArrayWithSurroundingText() {
            String response = "Here are the relevant files:\n[\"docs/api.md\", \"docs/guide.md\"]\nThat's all.";
            List<String> result = gateway.parseJsonArrayOfStrings(response);
            assertEquals(List.of("docs/api.md", "docs/guide.md"), result);
        }

        @Test
        void returnsEmptyListForNoArray() {
            List<String> result = gateway.parseJsonArrayOfStrings("No relevant files found.");
            assertTrue(result.isEmpty());
        }
    }

    // ---- parseReviewDecision ----

    @Nested
    class ParseReviewDecision {

        @Test
        void parsesAutonomousResponse() {
            String response = "AUTONOMOUS: [{\"path\":\"docs/readme.md\",\"newContent\":\"# New README\"}]";
            ReviewDecision decision = gateway.parseReviewDecision(response);

            assertInstanceOf(ReviewDecision.Autonomous.class, decision);
            ReviewDecision.Autonomous autonomous = (ReviewDecision.Autonomous) decision;
            assertEquals(1, autonomous.updates().size());
            assertEquals("docs/readme.md", autonomous.updates().get(0).path());
            assertEquals("# New README", autonomous.updates().get(0).newContent());
        }

        @Test
        void parsesQuestionResponse() {
            String response = "QUESTION: What is the purpose of this refactoring?";
            ReviewDecision decision = gateway.parseReviewDecision(response);

            assertInstanceOf(ReviewDecision.NeedsInput.class, decision);
            assertEquals("What is the purpose of this refactoring?",
                    ((ReviewDecision.NeedsInput) decision).question());
        }

        @Test
        void treatsUnexpectedFormatAsNeedsInput() {
            String response = "I'm not sure what to do with this diff.";
            ReviewDecision decision = gateway.parseReviewDecision(response);

            assertInstanceOf(ReviewDecision.NeedsInput.class, decision);
            assertEquals("I'm not sure what to do with this diff.",
                    ((ReviewDecision.NeedsInput) decision).question());
        }
    }
}
