package com.example.documenter.documentation.domain;

/**
 * Represents a pending update to a documentation file.
 *
 * @param path       the file path relative to the documentation repository root
 * @param newContent the updated Markdown content to write
 */
public record DocUpdate(String path, String newContent) {
}
