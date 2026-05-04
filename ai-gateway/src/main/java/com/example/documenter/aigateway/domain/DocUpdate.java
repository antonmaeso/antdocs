package com.example.documenter.aigateway.domain;

/**
 * Represents a pending update to a documentation file.
 *
 * <p>This is a local copy of the documentation module's {@code DocUpdate} record,
 * kept separate to avoid a cross-module dependency. Both modules use the same shape
 * and values are mapped at the boundary between modules.</p>
 *
 * @param path       the file path relative to the documentation repository root
 * @param newContent the updated Markdown content to write
 */
public record DocUpdate(String path, String newContent) {
}
