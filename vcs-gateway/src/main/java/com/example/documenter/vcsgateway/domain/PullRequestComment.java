package com.example.documenter.vcsgateway.domain;

/**
 * Represents a comment on a pull request.
 *
 * @param body   the comment body text
 * @param path   the file path the comment is associated with (may be null for general comments)
 * @param line   the line number the comment refers to (0 if not line-specific)
 * @param author the username of the comment author
 * @param id     the unique identifier of the comment
 */
public record PullRequestComment(String body, String path, int line, String author, long id) {
}
