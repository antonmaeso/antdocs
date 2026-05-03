package com.example.documenter.aigateway.domain;

/**
 * A lightweight summary of a documentation file, used during relevance scanning.
 *
 * <p>This is a local copy of the documentation module's {@code DocFileSummary} record,
 * kept separate to avoid a cross-module dependency. Both modules use the same shape
 * and values are mapped at the boundary between modules.</p>
 *
 * @param path             the file path relative to the documentation repository root
 * @param shortDescription a one-sentence description of the file's content
 */
public record DocFileSummary(String path, String shortDescription) {
}
