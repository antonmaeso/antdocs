package com.example.documenter.vcsgateway.domain;

/**
 * Represents the diff for a single file within a pull request.
 *
 * @param path  the file path relative to the repository root
 * @param patch the unified diff patch content for this file
 */
public record FileDiff(String path, String patch) {
}
