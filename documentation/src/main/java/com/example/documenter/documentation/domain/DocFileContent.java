package com.example.documenter.documentation.domain;

/**
 * Holds the full Markdown content of a documentation file.
 *
 * @param path            the file path relative to the documentation repository root
 * @param markdownContent the complete Markdown content of the file
 */
public record DocFileContent(String path, String markdownContent) {
}
