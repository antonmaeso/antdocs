package com.example.documenter.aigateway;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PromptLoader}.
 * Verifies template loading, variable substitution, and error handling.
 */
class PromptLoaderTest {

    private PromptLoader promptLoader;

    @BeforeEach
    void setUp() {
        promptLoader = new PromptLoader();
    }

    // ---- load() with real .st files ----

    @Test
    void loadGenerateDocumentationTemplate_substitutesVariables() {
        String result = promptLoader.load("generate-documentation.st", Map.of(
                "filePath", "src/Main.java",
                "fileContent", "public class Main {}"
        ));

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("src/Main.java"), "Should contain substituted filePath");
        assertTrue(result.contains("public class Main {}"), "Should contain substituted fileContent");
        assertFalse(result.contains("{filePath}"), "Should not contain unsubstituted variable");
        assertFalse(result.contains("{fileContent}"), "Should not contain unsubstituted variable");
    }

    @Test
    void loadGenerateReadmeTemplate_substitutesVariables() {
        String result = promptLoader.load("generate-readme.st", Map.of(
                "repoName", "my-awesome-repo",
                "fileSummaries", "File1: Does X\nFile2: Does Y"
        ));

        assertNotNull(result);
        assertTrue(result.contains("my-awesome-repo"));
        assertTrue(result.contains("File1: Does X"));
        assertFalse(result.contains("{repoName}"));
        assertFalse(result.contains("{fileSummaries}"));
    }

    @Test
    void loadScanRelevantDocsTemplate_substitutesVariables() {
        String result = promptLoader.load("scan-relevant-docs.st", Map.of(
                "diffPatch", "+added line\n-removed line",
                "docSummaries", "docs/api.md: API reference"
        ));

        assertNotNull(result);
        assertTrue(result.contains("+added line"));
        assertTrue(result.contains("docs/api.md: API reference"));
        assertFalse(result.contains("{diffPatch}"));
        assertFalse(result.contains("{docSummaries}"));
    }

    @Test
    void loadAnalyseDiffTemplate_substitutesVariables() {
        String result = promptLoader.load("analyse-diff.st", Map.of(
                "diffPatch", "diff --git a/file.java b/file.java",
                "relevantDocs", "--- docs/api.md ---\n# API",
                "history", "Q: What changed?\nA: Added a method."
        ));

        assertNotNull(result);
        assertTrue(result.contains("diff --git a/file.java b/file.java"));
        assertTrue(result.contains("--- docs/api.md ---"));
        assertTrue(result.contains("Q: What changed?"));
        assertFalse(result.contains("{diffPatch}"));
        assertFalse(result.contains("{relevantDocs}"));
        assertFalse(result.contains("{history}"));
    }

    // ---- load() with non-existent file ----

    @Test
    void loadNonExistentFile_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                promptLoader.load("non-existent-template.st", Map.of())
        );

        assertTrue(ex.getMessage().contains("non-existent-template.st"),
                "Exception message should contain the missing filename");
    }

    // ---- exists() ----

    @Test
    void existsReturnsTrueForExistingFile() {
        assertTrue(promptLoader.exists("generate-documentation.st"));
        assertTrue(promptLoader.exists("generate-readme.st"));
        assertTrue(promptLoader.exists("scan-relevant-docs.st"));
        assertTrue(promptLoader.exists("analyse-diff.st"));
        assertTrue(promptLoader.exists("pr-timeout-followup.st"));
    }

    @Test
    void existsReturnsFalseForNonExistentFile() {
        assertFalse(promptLoader.exists("does-not-exist.st"));
        assertFalse(promptLoader.exists("random-template.st"));
    }
}
