package com.example.documenter.documentation.domain;

/**
 * A lightweight summary of a documentation file, used during relevance scanning.
 *
 * @param path             the file path relative to the documentation repository root
 * @param shortDescription a one-sentence description of the file's content
 */
public record DocFileSummary(String path, String shortDescription) {
}
