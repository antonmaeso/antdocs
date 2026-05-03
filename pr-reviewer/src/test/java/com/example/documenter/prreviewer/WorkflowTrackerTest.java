package com.example.documenter.prreviewer;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.documenter.prreviewer.domain.TrackedPR;

/**
 * Unit tests for {@link WorkflowTracker}.
 */
class WorkflowTrackerTest {

    private WorkflowTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new WorkflowTracker();
    }

    @Test
    void registerNewEntry() {
        tracker.register("owner", "repo", 1, "wf-1");

        Optional<String> result = tracker.findWorkflowId("owner", "repo", 1);
        assertTrue(result.isPresent());
        assertEquals("wf-1", result.get());
    }

    @Test
    void registerDuplicateIsIdempotent() {
        tracker.register("owner", "repo", 1, "wf-1");
        tracker.register("owner", "repo", 1, "wf-2");

        Optional<String> result = tracker.findWorkflowId("owner", "repo", 1);
        assertTrue(result.isPresent());
        assertEquals("wf-2", result.get());
    }

    @Test
    void findAbsentEntryReturnsEmpty() {
        Optional<String> result = tracker.findWorkflowId("owner", "repo", 99);
        assertTrue(result.isEmpty());
    }

    @Test
    void removePresentEntry() {
        tracker.register("owner", "repo", 1, "wf-1");
        tracker.remove("owner", "repo", 1);

        assertTrue(tracker.findWorkflowId("owner", "repo", 1).isEmpty());
    }

    @Test
    void removeAbsentEntryIsNoOp() {
        assertDoesNotThrow(() -> tracker.remove("owner", "repo", 99));
    }

    @Test
    void allTrackedReturnsSnapshot() {
        tracker.register("owner", "repo", 1, "wf-1");
        tracker.register("owner", "repo", 2, "wf-2");

        Set<TrackedPR> snapshot = tracker.allTracked();
        assertEquals(2, snapshot.size());

        // Modifying tracker after snapshot should not affect snapshot
        tracker.register("owner", "repo", 3, "wf-3");
        assertEquals(2, snapshot.size());
    }

    @Test
    void updateLastSeenCommentId() {
        tracker.register("owner", "repo", 1, "wf-1");
        tracker.updateLastSeenCommentId("owner", "repo", 1, 42L);

        Optional<TrackedPR> tracked = tracker.findTrackedPR("owner", "repo", 1);
        assertTrue(tracked.isPresent());
        assertEquals(42L, tracked.get().lastSeenCommentId());
    }

    @Test
    void updateLastSeenCommentIdForAbsentEntryIsNoOp() {
        assertDoesNotThrow(() ->
                tracker.updateLastSeenCommentId("owner", "repo", 99, 42L));
    }

    @Test
    void concurrentRegisterAndRemoveDoesNotThrow() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int prNumber = i;
            executor.submit(() -> {
                try {
                    tracker.register("owner", "repo", prNumber, "wf-" + prNumber);
                    tracker.findWorkflowId("owner", "repo", prNumber);
                    tracker.allTracked();
                    if (prNumber % 2 == 0) {
                        tracker.remove("owner", "repo", prNumber);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        // No exception means the test passes
    }
}
