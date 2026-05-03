package com.example.documenter.documentation.domain;

import java.time.Instant;

/**
 * Tracks the state of a documentation generation run.
 *
 * @param runId     unique identifier for this run
 * @param owner     the repository owner (user or organisation)
 * @param repo      the repository name
 * @param startedAt the instant the run was started
 * @param status    the current status of the run
 * @param summary   a human-readable summary or error message
 */
public record DocumentationRun(String runId, String owner, String repo, Instant startedAt, RunStatus status,
                                String summary) {
}
