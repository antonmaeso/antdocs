package com.example.documenter.prreviewer;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.documenter.prreviewer.domain.TrackedPR;

/**
 * In-memory registry of pull requests currently being tracked by the PR reviewer.
 *
 * <p>Maps {@code "{owner}/{repo}/pr/{prNumber}"} to a {@link TrackedPR} record.
 * Thread-safe — backed by a {@link ConcurrentHashMap}.
 */
@Component
public class WorkflowTracker {

    private final ConcurrentHashMap<String, TrackedPR> tracked = new ConcurrentHashMap<>();

    /**
     * Registers a PR with its associated workflow ID. If the key already exists,
     * the entry is overwritten (idempotent).
     */
    public void register(String owner, String repo, int prNumber, String workflowId) {
        String key = buildKey(owner, repo, prNumber);
        tracked.put(key, new TrackedPR(owner, repo, prNumber, workflowId, 0L));
    }

    /**
     * Returns the workflow ID for a tracked PR, or empty if not tracked.
     */
    public Optional<String> findWorkflowId(String owner, String repo, int prNumber) {
        String key = buildKey(owner, repo, prNumber);
        TrackedPR entry = tracked.get(key);
        return entry != null ? Optional.of(entry.workflowId()) : Optional.empty();
    }

    /**
     * Removes a PR from the tracker. No-op if not present.
     */
    public void remove(String owner, String repo, int prNumber) {
        String key = buildKey(owner, repo, prNumber);
        tracked.remove(key);
    }

    /**
     * Returns an unmodifiable snapshot of all currently tracked PRs.
     */
    public Set<TrackedPR> allTracked() {
        return Collections.unmodifiableSet(
                tracked.values().stream().collect(Collectors.toSet())
        );
    }

    /**
     * Atomically updates the last-seen comment ID for a tracked PR.
     * No-op if the PR is not currently tracked.
     */
    public void updateLastSeenCommentId(String owner, String repo, int prNumber, long commentId) {
        String key = buildKey(owner, repo, prNumber);
        tracked.computeIfPresent(key, (k, existing) ->
                new TrackedPR(existing.owner(), existing.repo(), existing.prNumber(),
                        existing.workflowId(), commentId));
    }

    /**
     * Returns the {@link TrackedPR} for the given coordinates, or empty if not tracked.
     */
    public Optional<TrackedPR> findTrackedPR(String owner, String repo, int prNumber) {
        String key = buildKey(owner, repo, prNumber);
        return Optional.ofNullable(tracked.get(key));
    }

    private String buildKey(String owner, String repo, int prNumber) {
        return owner + "/" + repo + "/pr/" + prNumber;
    }
}
