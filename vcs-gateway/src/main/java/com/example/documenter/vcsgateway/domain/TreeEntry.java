package com.example.documenter.vcsgateway.domain;

/**
 * Represents a single entry in a VCS file tree.
 *
 * @param path the file or directory path relative to the repository root
 * @param type the entry type: "blob" (file), "tree" (directory), or "commit" (submodule)
 * @param sha  the SHA hash of the entry
 */
public record TreeEntry(String path, String type, String sha) {
}
