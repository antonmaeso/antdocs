package com.example.documenter.aigateway;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for all five prompt template files.
 * Verifies each {@code .st} file is loadable from the classpath and renders
 * without error with representative variable maps.
 */
class PromptTemplateIntegrationTest {

    private PromptLoader promptLoader;

    @BeforeEach
    void setUp() {
        promptLoader = new PromptLoader();
    }

    // ---- generate-documentation.st ----

    @Test
    void generateDocumentationTemplate_isLoadableAndRendersCorrectly() {
        String result = promptLoader.load("generate-documentation.st", Map.of(
                "filePath", "src/com/example/UserService.java",
                "fileContent", "public class UserService {\n    public User findById(long id) { return null; }\n}"
        ));

        assertNotNull(result);
        assertFalse(result.isBlank(), "Rendered template should not be blank");
        assertTrue(result.contains("src/com/example/UserService.java"),
                "Rendered output should contain the substituted filePath");
        assertTrue(result.contains("public class UserService"),
                "Rendered output should contain the substituted fileContent");
    }

    // ---- generate-readme.st ----

    @Test
    void generateReadmeTemplate_isLoadableAndRendersCorrectly() {
        String result = promptLoader.load("generate-readme.st", Map.of(
                "repoName", "github-repo-documenter",
                "fileSummaries", "UserService.java: Manages user CRUD operations\nOrderService.java: Handles order processing"
        ));

        assertNotNull(result);
        assertFalse(result.isBlank());
        assertTrue(result.contains("github-repo-documenter"),
                "Rendered output should contain the substituted repoName");
        assertTrue(result.contains("UserService.java: Manages user CRUD operations"),
                "Rendered output should contain the substituted fileSummaries");
    }

    // ---- scan-relevant-docs.st ----

    @Test
    void scanRelevantDocsTemplate_isLoadableAndRendersCorrectly() {
        String diffPatch = """
                diff --git a/src/UserService.java b/src/UserService.java
                --- a/src/UserService.java
                +++ b/src/UserService.java
                @@ -1,3 +1,4 @@
                 public class UserService {
                +    public void deleteUser(long id) {}
                 }""";

        String result = promptLoader.load("scan-relevant-docs.st", Map.of(
                "diffPatch", diffPatch,
                "docSummaries", "docs/user-service.md: Documents the UserService class\ndocs/api-reference.md: API endpoint reference"
        ));

        assertNotNull(result);
        assertFalse(result.isBlank());
        assertTrue(result.contains("UserService"),
                "Rendered output should contain diff content");
        assertTrue(result.contains("docs/user-service.md"),
                "Rendered output should contain doc summaries");
    }

    // ---- analyse-diff.st ----

    @Test
    void analyseDiffTemplate_isLoadableAndRendersCorrectly() {
        String result = promptLoader.load("analyse-diff.st", Map.of(
                "diffPatch", "+public void newMethod() {}",
                "relevantDocs", "--- docs/api.md ---\n# API Reference\nExisting documentation content.",
                "history", "Q: What does this method do?\nA: It processes user requests."
        ));

        assertNotNull(result);
        assertFalse(result.isBlank());
        assertTrue(result.contains("+public void newMethod() {}"),
                "Rendered output should contain the substituted diffPatch");
        assertTrue(result.contains("--- docs/api.md ---"),
                "Rendered output should contain the substituted relevantDocs");
        assertTrue(result.contains("Q: What does this method do?"),
                "Rendered output should contain the substituted history");
    }

    // ---- pr-timeout-followup.st ----

    @Test
    void prTimeoutFollowupTemplate_isLoadableAndRendersCorrectly() {
        // This template has no variables — just verify it loads and is non-empty
        String result = promptLoader.load("pr-timeout-followup.st", Map.of());

        assertNotNull(result);
        assertFalse(result.isBlank(), "Rendered template should not be blank");
        assertTrue(result.contains("timeout") || result.contains("follow-up") || result.contains("automated"),
                "Rendered output should contain expected timeout-related content");
    }

    // ---- All templates exist on classpath ----

    @Test
    void allFiveTemplatesExistOnClasspath() {
        assertTrue(promptLoader.exists("generate-documentation.st"), "generate-documentation.st should exist");
        assertTrue(promptLoader.exists("generate-readme.st"), "generate-readme.st should exist");
        assertTrue(promptLoader.exists("scan-relevant-docs.st"), "scan-relevant-docs.st should exist");
        assertTrue(promptLoader.exists("analyse-diff.st"), "analyse-diff.st should exist");
        assertTrue(promptLoader.exists("pr-timeout-followup.st"), "pr-timeout-followup.st should exist");
    }
}
