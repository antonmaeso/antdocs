package com.example.documenter.documentation;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.crawler.CrawledFile;
import com.example.documenter.documentation.domain.DocumentationArtifact;

/**
 * Generates per-file documentation and a top-level README using the AI gateway.
 *
 * <p>For each crawled file, calls {@link AIGateway#generateDocumentation} with retry
 * (up to 3 attempts, exponential backoff: 1s, 2s, 4s). After all files are processed,
 * collects short descriptions and calls {@link AIGateway#generateRepositoryReadme}.
 */
@Service
public class DocumentationGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocumentationGenerator.class);
    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000};

    private final AIGateway aiGateway;

    public DocumentationGenerator(AIGateway aiGateway) {
        this.aiGateway = aiGateway;
    }

    /**
     * Generates documentation artifacts for all crawled files plus a repository README.
     *
     * @param files    the list of crawled source files
     * @param repoName the repository name (used for README generation)
     * @return a list of documentation artifacts including per-file docs and a README
     */
    public List<DocumentationArtifact> generate(List<CrawledFile> files, String repoName) {
        List<DocumentationArtifact> artifacts = new ArrayList<>();

        for (CrawledFile file : files) {
            DocumentationArtifact artifact = generateWithRetry(file);
            if (artifact != null) {
                artifacts.add(artifact);
            }
        }

        // Generate repository README from all short descriptions
        List<String> summaries = artifacts.stream()
                .map(DocumentationArtifact::shortDescription)
                .toList();

        String readmeContent = aiGateway.generateRepositoryReadme(summaries, repoName);
        artifacts.add(new DocumentationArtifact("README.md", readmeContent, "Repository overview and structure"));

        return artifacts;
    }

    private DocumentationArtifact generateWithRetry(CrawledFile file) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                String markdown = aiGateway.generateDocumentation(file.content(), file.path());
                String shortDescription = extractShortDescription(markdown);
                return new DocumentationArtifact(file.path(), markdown, shortDescription);
            } catch (Exception e) {
                log.warn("AI documentation generation failed for {} (attempt {}/{}): {}",
                        file.path(), attempt + 1, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    sleep(BACKOFF_MS[attempt]);
                }
            }
        }
        log.error("Skipping file {} after {} failed attempts", file.path(), MAX_RETRIES);
        return null;
    }

    /**
     * Extracts a short description from the generated markdown.
     * Takes the first sentence after the first heading, or the first non-empty line.
     */
    static String extractShortDescription(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        String[] lines = markdown.split("\n");
        boolean pastHeading = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                pastHeading = true;
                continue;
            }
            if (pastHeading && !trimmed.isEmpty()) {
                // Return the first sentence (up to the first period, or the whole line)
                int periodIndex = trimmed.indexOf('.');
                if (periodIndex > 0) {
                    return trimmed.substring(0, periodIndex + 1);
                }
                return trimmed;
            }
        }

        // Fallback: return the first non-empty, non-heading line
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                int periodIndex = trimmed.indexOf('.');
                if (periodIndex > 0) {
                    return trimmed.substring(0, periodIndex + 1);
                }
                return trimmed;
            }
        }

        return markdown.trim().split("\n")[0];
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
