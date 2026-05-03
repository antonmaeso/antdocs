package com.example.documenter.documentation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.crawler.CrawledFile;
import com.example.documenter.documentation.domain.DocumentationArtifact;

/**
 * Unit tests for {@link DocumentationGenerator}.
 */
@ExtendWith(MockitoExtension.class)
class DocumentationGeneratorTest {

    @Mock
    private AIGateway aiGateway;

    private DocumentationGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new DocumentationGenerator(aiGateway);
    }

    @Test
    void generate_producesArtifactForEachFile() {
        CrawledFile file1 = new CrawledFile("src/Main.java", "public class Main {}");
        CrawledFile file2 = new CrawledFile("src/Utils.java", "public class Utils {}");

        when(aiGateway.generateDocumentation("public class Main {}", "src/Main.java"))
                .thenReturn("# Main\nEntry point of the application.");
        when(aiGateway.generateDocumentation("public class Utils {}", "src/Utils.java"))
                .thenReturn("# Utils\nUtility helper class.");
        when(aiGateway.generateRepositoryReadme(anyList(), eq("my-repo")))
                .thenReturn("# my-repo\nOverview");

        List<DocumentationArtifact> result = generator.generate(List.of(file1, file2), "my-repo");

        // 2 file artifacts + 1 README
        assertEquals(3, result.size());
        assertEquals("src/Main.java", result.get(0).sourcePath());
        assertEquals("src/Utils.java", result.get(1).sourcePath());
        assertEquals("README.md", result.get(2).sourcePath());
    }

    @Test
    void generate_retryOnFailure_threeFailuresSkipsFile() {
        CrawledFile file = new CrawledFile("src/Broken.java", "broken content");

        when(aiGateway.generateDocumentation("broken content", "src/Broken.java"))
                .thenThrow(new RuntimeException("AI error"));
        when(aiGateway.generateRepositoryReadme(anyList(), eq("repo")))
                .thenReturn("# repo\nReadme");

        List<DocumentationArtifact> result = generator.generate(List.of(file), "repo");

        // Only the README should be present (file was skipped after 3 failures)
        assertEquals(1, result.size());
        assertEquals("README.md", result.get(0).sourcePath());

        // Verify 3 retry attempts
        verify(aiGateway, times(3)).generateDocumentation("broken content", "src/Broken.java");
    }

    @Test
    void generate_retrySucceedsOnSecondAttempt() {
        CrawledFile file = new CrawledFile("src/Retry.java", "retry content");

        when(aiGateway.generateDocumentation("retry content", "src/Retry.java"))
                .thenThrow(new RuntimeException("Transient error"))
                .thenReturn("# Retry\nRecovered successfully.");
        when(aiGateway.generateRepositoryReadme(anyList(), eq("repo")))
                .thenReturn("# repo\nReadme");

        List<DocumentationArtifact> result = generator.generate(List.of(file), "repo");

        assertEquals(2, result.size());
        assertEquals("src/Retry.java", result.get(0).sourcePath());
        verify(aiGateway, times(2)).generateDocumentation("retry content", "src/Retry.java");
    }

    @Test
    void generate_readmeCalledAfterAllFiles() {
        CrawledFile file = new CrawledFile("src/App.java", "app content");

        when(aiGateway.generateDocumentation("app content", "src/App.java"))
                .thenReturn("# App\nThe main application class.");
        when(aiGateway.generateRepositoryReadme(anyList(), eq("repo")))
                .thenReturn("# repo\nReadme content");

        List<DocumentationArtifact> result = generator.generate(List.of(file), "repo");

        // README should be the last artifact
        DocumentationArtifact readme = result.get(result.size() - 1);
        assertEquals("README.md", readme.sourcePath());

        // Verify readme was called with the short descriptions
        verify(aiGateway).generateRepositoryReadme(anyList(), eq("repo"));
    }

    @Test
    void generate_shortDescriptionPopulated() {
        CrawledFile file = new CrawledFile("src/Service.java", "service code");

        when(aiGateway.generateDocumentation("service code", "src/Service.java"))
                .thenReturn("# Service\nHandles business logic for the application. It processes requests.");
        when(aiGateway.generateRepositoryReadme(anyList(), eq("repo")))
                .thenReturn("# repo\nReadme");

        List<DocumentationArtifact> result = generator.generate(List.of(file), "repo");

        DocumentationArtifact artifact = result.get(0);
        assertNotNull(artifact.shortDescription());
        assertFalse(artifact.shortDescription().isEmpty());
        assertEquals("Handles business logic for the application.", artifact.shortDescription());
    }

    @Test
    void generate_emptyFileList_onlyReadmeProduced() {
        when(aiGateway.generateRepositoryReadme(anyList(), eq("repo")))
                .thenReturn("# repo\nEmpty repo");

        List<DocumentationArtifact> result = generator.generate(List.of(), "repo");

        assertEquals(1, result.size());
        assertEquals("README.md", result.get(0).sourcePath());
        verify(aiGateway, never()).generateDocumentation(anyString(), anyString());
    }

    @Test
    void extractShortDescription_firstSentenceAfterHeading() {
        String markdown = "# Title\nThis is the first sentence. And this is the second.";
        assertEquals("This is the first sentence.", DocumentationGenerator.extractShortDescription(markdown));
    }

    @Test
    void extractShortDescription_noHeading_firstLine() {
        String markdown = "A standalone description without heading.";
        assertEquals("A standalone description without heading.", DocumentationGenerator.extractShortDescription(markdown));
    }

    @Test
    void extractShortDescription_blankMarkdown_returnsEmpty() {
        assertEquals("", DocumentationGenerator.extractShortDescription(""));
        assertEquals("", DocumentationGenerator.extractShortDescription("   "));
    }
}
