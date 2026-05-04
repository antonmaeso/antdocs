package com.example.documenter.aigateway.domain;

/**
 * Holds the full Markdown content of a documentation file.
 *
 * <p>This is a local copy of the documentation module's {@code DocFileContent} record,
 * kept separate to avoid a cross-module dependency. Both modules use the same shape
 * and values are mapped at the boundary between modules.</p>
 *
 * @param path            the file path relative to the documentation repository root
 * @param markdownContent the complete Markdown content of the file
 */
public record DocFileContent(String path, String markdownContent) {
}
