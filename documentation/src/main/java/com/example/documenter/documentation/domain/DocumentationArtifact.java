package com.example.documenter.documentation.domain;

/**
 * Represents a single generated documentation artifact for a source file.
 *
 * @param sourcePath       the path of the source file relative to the repository root
 * @param markdownContent  the AI-generated Markdown documentation for the file
 * @param shortDescription a one-sentence summary used by the relevance scanner
 */
public record DocumentationArtifact(String sourcePath, String markdownContent, String shortDescription) {
}
