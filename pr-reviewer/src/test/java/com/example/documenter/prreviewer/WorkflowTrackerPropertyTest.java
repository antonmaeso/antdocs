package com.example.documenter.prreviewer;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link WorkflowTracker}.
 *
 * <p>Verifies that for any sequence of register and remove operations,
 * the tracker correctly reports which PRs are tracked and which are not.
 */
class WorkflowTrackerPropertyTest {

    /**
     * Represents a single operation on the WorkflowTracker.
     */
    sealed interface TrackerOp permits RegisterOp, RemoveOp {}

    record RegisterOp(String owner, String repo, int prNumber, String workflowId) implements TrackerOp {}
    record RemoveOp(String owner, String repo, int prNumber) implements TrackerOp {}

    @Provide
    Arbitrary<List<TrackerOp>> trackerOperations() {
        Arbitrary<String> owners = Arbitraries.of("org-a", "org-b", "user-x");
        Arbitrary<String> repos = Arbitraries.of("repo-1", "repo-2", "repo-3");
        Arbitrary<Integer> prNumbers = Arbitraries.integers().between(1, 50);
        Arbitrary<String> workflowIds = Arbitraries.strings().alpha().ofLength(10)
                .map(s -> "wf-" + s);

        Arbitrary<TrackerOp> registerOp = Combinators.combine(owners, repos, prNumbers, workflowIds)
                .as(RegisterOp::new);

        Arbitrary<TrackerOp> removeOp = Combinators.combine(owners, repos, prNumbers)
                .as(RemoveOp::new);

        return Arbitraries.oneOf(registerOp, removeOp)
                .list().ofMinSize(1).ofMaxSize(50);
    }

    // ---- Property 11: trackerRegistersAndDeregistersCorrectly ----

    /**
     * For any sequence of register and remove operations on the WorkflowTracker,
     * {@code findWorkflowId} returns the registered workflow ID for any PR that
     * has been registered and not yet removed, and returns empty for any PR that
     * has been removed or never registered.
     *
     * <p>Validates: Requirements 4.3, 4.4
     */
    @Property(tries = 100)
    // Feature: github-repo-documenter, Property 11: trackerRegistersAndDeregistersCorrectly
    void trackerRegistersAndDeregistersCorrectly(
            @ForAll("trackerOperations") List<TrackerOp> operations) {

        WorkflowTracker tracker = new WorkflowTracker();

        // Track expected state: key -> latest workflowId (null means removed)
        record PRKey(String owner, String repo, int prNumber) {}
        java.util.Map<PRKey, String> expectedState = new java.util.HashMap<>();

        // Apply all operations to both the tracker and our expected-state model
        for (TrackerOp op : operations) {
            switch (op) {
                case RegisterOp reg -> {
                    tracker.register(reg.owner(), reg.repo(), reg.prNumber(), reg.workflowId());
                    expectedState.put(new PRKey(reg.owner(), reg.repo(), reg.prNumber()), reg.workflowId());
                }
                case RemoveOp rem -> {
                    tracker.remove(rem.owner(), rem.repo(), rem.prNumber());
                    expectedState.remove(new PRKey(rem.owner(), rem.repo(), rem.prNumber()));
                }
            }
        }

        // Verify: every key in expectedState should be findable with the correct workflowId
        for (var entry : expectedState.entrySet()) {
            PRKey key = entry.getKey();
            String expectedWfId = entry.getValue();

            Optional<String> actual = tracker.findWorkflowId(key.owner(), key.repo(), key.prNumber());
            assertTrue(actual.isPresent(),
                    "PR " + key + " should be tracked but was not found");
            assertEquals(expectedWfId, actual.get(),
                    "PR " + key + " should have workflowId=" + expectedWfId + " but had " + actual.get());
        }

        // Verify: allTracked() size matches expected state size
        Set<com.example.documenter.prreviewer.domain.TrackedPR> allTracked = tracker.allTracked();
        assertEquals(expectedState.size(), allTracked.size(),
                "allTracked() size should match number of registered-and-not-removed PRs");

        // Verify: PRs that were removed (or never registered) are not findable
        // Collect all PR keys that were ever referenced in operations
        Set<PRKey> allReferencedKeys = new HashSet<>();
        for (TrackerOp op : operations) {
            switch (op) {
                case RegisterOp reg -> allReferencedKeys.add(new PRKey(reg.owner(), reg.repo(), reg.prNumber()));
                case RemoveOp rem -> allReferencedKeys.add(new PRKey(rem.owner(), rem.repo(), rem.prNumber()));
            }
        }

        for (PRKey key : allReferencedKeys) {
            if (!expectedState.containsKey(key)) {
                Optional<String> actual = tracker.findWorkflowId(key.owner(), key.repo(), key.prNumber());
                assertTrue(actual.isEmpty(),
                        "PR " + key + " was removed or never registered, but findWorkflowId returned a value");
            }
        }
    }
}
