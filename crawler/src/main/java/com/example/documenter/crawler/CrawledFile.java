package com.example.documenter.crawler;

/**
 * Represents a file that has been successfully crawled from a repository.
 *
 * @param path    the file path relative to the repository root
 * @param content the decoded file content
 */
public record CrawledFile(String path, String content) {
}
