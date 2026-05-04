package com.example.documenter.documentation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.crawler.CrawledFile;
import com.example.documenter.documentation.domain.DocumentationArtifact;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link DocumentationGenerator}.
 *
 * <p>Uses jqwik to verify universal invariants across randomly generated inputs.
 */
class DocumentationGeneratorPropertyTest {

    private static final String[] EXTENSIONS = {".java", ".kt", ".py", ".ts", ".js", ".go"};

    @Provide
    Arbitrary<String> filePaths() {
        Arbitrary<String> dirs = Arbitraries.of("src", "lib", "test", "src/main", "src/test/java");
        Arbitrary<String> names = Arbitraries.of("Main", "App", "Utils", "Config", "Service", "Controller");
        Arbitrary<String> exts = Arbitraries.of(EXTENSIONS);
        return Combinators.combine(dirs, names, exts).as((dir, name, ext) -> dir + "/" + name + ext);
    }

    @Provide
    Arbitrary<List<CrawledFile>> crawledFiles() {
        Arbitrary<CrawledFile> fileArb = Combinators.combine(
                filePaths(),
                Arbitraries.of("public class Main {}", "def hello():", "function main() {}", "package main")
        ).as(CrawledFile::new);

        return fileArb.list().ofMinSize(1).ofMaxSize(10)
                .map(files -> files.stream()
                        .collect(Collectors.toMap(CrawledFile::path, f -> f, (a, b) -> a))
                        .values().stream().toList());
    }

    // ---- Property 5: documentationGeneratedForEveryFile ----

    /**
     * For any non-empty list of source files, the DocumentationGenerator produces a
     * DocumentationArtifact for each file, and the set of artifact source paths equals
     * the set of input file paths (plus README.md).
     *
     * <p>Validates: Requirements 2.1
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 5: documentationGeneratedForEveryFile
    void documentationGeneratedForEveryFile(@ForAll("crawledFiles") List<CrawledFile> files) {
        AIGateway aiGateway = mock(AIGateway.class);

        // Mock AI to return valid markdown for every file
        when(aiGateway.generateDocumentation(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    return "# " + path + "\nDocumentation for " + path + ".";
                });
        when(aiGateway.generateRepositoryReadme(anyList(), anyString()))
                .thenReturn("# repo\nRepository overview.");

        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        List<DocumentationArtifact> result = generator.generate(files, "test-repo");

        // Collect source paths from input files
        Set<String> inputPaths = files.stream().map(CrawledFile::path).collect(Collectors.toSet());

        // Collect source paths from artifacts (excluding README)
        Set<String> artifactPaths = result.stream()
                .map(DocumentationArtifact::sourcePath)
                .filter(p -> !"README.md".equals(p))
                .collect(Collectors.toSet());

        assertEquals(inputPaths, artifactPaths,
                "Artifact source paths should match input file paths");

        // README should always be present
        assertTrue(result.stream().anyMatch(a -> "README.md".equals(a.sourcePath())),
                "README.md artifact should always be present");

        // Total count: files + README
        assertEquals(files.size() + 1, result.size());
    }

    // ---- Property 6: generatedOutputIsNonEmptyMarkdownWithDescription ----

    /**
     * For any source file content, the resulting DocumentationArtifact.markdownContent
     * is a non-empty string containing at least one Markdown heading (#), and
     * DocumentationArtifact.shortDescription is a non-empty, non-null string.
     *
     * <p>Validates: Requirements 2.4, 2.8
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 6: generatedOutputIsNonEmptyMarkdownWithDescription
    void generatedOutputIsNonEmptyMarkdownWithDescription(@ForAll("crawledFiles") List<CrawledFile> files) {
        AIGateway aiGateway = mock(AIGateway.class);

        // Mock AI to return markdown with heading and description
        when(aiGateway.generateDocumentation(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    return "# " + path + "\nThis file provides core functionality. It handles requests and responses.";
                });
        when(aiGateway.generateRepositoryReadme(anyList(), anyString()))
                .thenReturn("# repo\nRepository overview.");

        DocumentationGenerator generator = new DocumentationGenerator(aiGateway);
        List<DocumentationArtifact> result = generator.generate(files, "test-repo");

        for (DocumentationArtifact artifact : result) {
            // markdownContent is non-empty
            assertNotNull(artifact.markdownContent(), "markdownContent should not be null");
            assertFalse(artifact.markdownContent().isEmpty(), "markdownContent should not be empty");

            // markdownContent contains at least one heading
            assertTrue(artifact.markdownContent().contains("#"),
                    "markdownContent should contain at least one Markdown heading");

            // shortDescription is non-empty and non-null
            assertNotNull(artifact.shortDescription(), "shortDescription should not be null");
            assertFalse(artifact.shortDescription().isEmpty(),
                    "shortDescription should not be empty for artifact: " + artifact.sourcePath());
        }
    }
}
