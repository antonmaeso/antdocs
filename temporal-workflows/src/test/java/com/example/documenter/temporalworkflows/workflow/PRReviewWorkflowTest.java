package com.example.documenter.temporalworkflows.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.example.documenter.aigateway.domain.DocUpdate;
import com.example.documenter.aigateway.domain.RelevanceScanResult;
import com.example.documenter.aigateway.domain.ReviewDecision;
import com.example.documenter.temporalworkflows.activity.AnalyseDiffActivity;
import com.example.documenter.temporalworkflows.activity.ApprovePRActivity;
import com.example.documenter.temporalworkflows.activity.PostCommentActivity;
import com.example.documenter.temporalworkflows.activity.ScanRelevantDocsActivity;
import com.example.documenter.temporalworkflows.activity.TimeoutActivity;
import com.example.documenter.temporalworkflows.activity.UpdateDocumentationActivity;
import com.example.documenter.temporalworkflows.domain.ConversationTurn;
import com.example.documenter.temporalworkflows.domain.PRReviewState;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

/**
 * Workflow tests for {@link PRReviewWorkflow} using Temporal's {@link TestWorkflowEnvironment}.
 *
 * <p>Tests cover happy paths (7.11), timeout and failure paths (7.12),
 * and conversation history correctness (7.13).</p>
 *
 * <p>Activity stubs are manual implementations (not Mockito mocks) because Temporal's
 * worker requires concrete implementations of {@code @ActivityInterface} interfaces.</p>
 */
class PRReviewWorkflowTest {

    private static final String TASK_QUEUE = "pr-review-test-queue";

    // ========================================================================
    // Activity stub implementations
    // ========================================================================

    private static class FixedScanStub implements ScanRelevantDocsActivity {
        private final List<String> relevantPaths;
        FixedScanStub(List<String> relevantPaths) { this.relevantPaths = relevantPaths; }
        @Override
        public RelevanceScanResult scan(String diffPatch, String owner, String repo) {
            return new RelevanceScanResult(relevantPaths);
        }
    }

    private static class SequentialAnalyseStub implements AnalyseDiffActivity {
        private final List<ReviewDecision> decisions;
        private final AtomicInteger callIndex = new AtomicInteger(0);
        private final List<List<ConversationTurn>> capturedHistories = new CopyOnWriteArrayList<>();

        SequentialAnalyseStub(List<ReviewDecision> decisions) {
            this.decisions = decisions;
        }

        @Override
        public ReviewDecision analyse(String diffPatch, List<String> relevantPaths,
                                      List<ConversationTurn> history, String owner, String repo) {
            capturedHistories.add(new ArrayList<>(history));
            int idx = callIndex.getAndIncrement();
            return decisions.get(Math.min(idx, decisions.size() - 1));
        }

        List<List<ConversationTurn>> getCapturedHistories() { return capturedHistories; }
    }

    private static class RecordingPostStub implements PostCommentActivity {
        private final List<String> postedQuestions = new CopyOnWriteArrayList<>();
        @Override
        public void post(String owner, String repo, int prNumber, String question) {
            postedQuestions.add(question);
        }
        List<String> getPostedQuestions() { return postedQuestions; }
    }

    private static class FailingPostStub implements PostCommentActivity {
        @Override
        public void post(String owner, String repo, int prNumber, String question) {
            throw new RuntimeException("Post comment failed");
        }
    }

    private static class RecordingUpdateStub implements UpdateDocumentationActivity {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final List<List<DocUpdate>> capturedUpdates = new CopyOnWriteArrayList<>();
        @Override
        public void update(List<DocUpdate> updates, String owner, String repo) {
            callCount.incrementAndGet();
            capturedUpdates.add(new ArrayList<>(updates));
        }
        int getCallCount() { return callCount.get(); }
    }

    private static class RecordingApproveStub implements ApprovePRActivity {
        private final AtomicInteger callCount = new AtomicInteger(0);
        @Override
        public void approve(String owner, String repo, int prNumber) {
            callCount.incrementAndGet();
        }
        int getCallCount() { return callCount.get(); }
    }

    private static class RecordingTimeoutStub implements TimeoutActivity {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final List<String> lastQuestions = new CopyOnWriteArrayList<>();
        @Override
        public void timeout(String owner, String repo, int prNumber, String lastQuestion) {
            callCount.incrementAndGet();
            lastQuestions.add(lastQuestion);
        }
        int getCallCount() { return callCount.get(); }
        List<String> getLastQuestions() { return lastQuestions; }
    }

    private PRReviewState createTestState() {
        return new PRReviewState("owner", "repo", 42, "diff-patch", List.of(), List.of());
    }

    // ========================================================================
    // Task 7.11: Happy path tests
    // ========================================================================

    @Test
    void autonomousPath_updatesDocsAndApprovesPR_noQuestionsPosted() {
        TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = testEnv.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(PRReviewWorkflowImpl.class);

            List<String> relevantPaths = List.of("docs/api.md", "docs/readme.md");
            List<DocUpdate> updates = List.of(new DocUpdate("docs/api.md", "updated content"));

            RecordingPostStub postStub = new RecordingPostStub();
            RecordingUpdateStub updateStub = new RecordingUpdateStub();
            RecordingApproveStub approveStub = new RecordingApproveStub();
            RecordingTimeoutStub timeoutStub = new RecordingTimeoutStub();

            worker.registerActivitiesImplementations(
                    new FixedScanStub(relevantPaths),
                    new SequentialAnalyseStub(List.of(new ReviewDecision.Autonomous(updates))),
                    postStub, updateStub, approveStub, timeoutStub);

            testEnv.start();
            WorkflowClient client = testEnv.getWorkflowClient();

            PRReviewWorkflow workflow = client.newWorkflowStub(
                    PRReviewWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("wf-autonomous")
                            .setTaskQueue(TASK_QUEUE)
                            .build());

            workflow.start(createTestState());

            assertEquals(1, updateStub.getCallCount(), "UpdateDocumentationActivity should be called once");
            assertEquals(1, approveStub.getCallCount(), "ApprovePRActivity should be called once");
            assertTrue(postStub.getPostedQuestions().isEmpty(), "No questions should be posted");
            assertEquals(0, timeoutStub.getCallCount(), "TimeoutActivity should not be called");
        } finally {
            testEnv.close();
        }
    }

    @Test
    void singleTurnClarification_postsOneQuestion_thenUpdatesAndApproves() {
        TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = testEnv.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(PRReviewWorkflowImpl.class);

            List<DocUpdate> updates = List.of(new DocUpdate("docs/api.md", "updated"));
            RecordingPostStub postStub = new RecordingPostStub();
            RecordingUpdateStub updateStub = new RecordingUpdateStub();
            RecordingApproveStub approveStub = new RecordingApproveStub();
            RecordingTimeoutStub timeoutStub = new RecordingTimeoutStub();

            worker.registerActivitiesImplementations(
                    new FixedScanStub(List.of("docs/api.md")),
                    new SequentialAnalyseStub(List.of(
                            new ReviewDecision.NeedsInput("What does this function do?"),
                            new ReviewDecision.Autonomous(updates))),
                    postStub, updateStub, approveStub, timeoutStub);

            testEnv.start();
            WorkflowClient client = testEnv.getWorkflowClient();

            PRReviewWorkflow workflow = client.newWorkflowStub(
                    PRReviewWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("wf-single-turn")
                            .setTaskQueue(TASK_QUEUE)
                            .build());

            CompletableFuture<Void> future = WorkflowClient.execute(workflow::start, createTestState());

            // Use registerDelayedCallback to send signal after workflow reaches await
            testEnv.registerDelayedCallback(Duration.ofSeconds(1), () -> {
                PRReviewWorkflow signalStub = client.newWorkflowStub(PRReviewWorkflow.class, "wf-single-turn");
                signalStub.developerReplySignal("It calculates the total price.");
            });

            // Advance time to trigger the callback
            testEnv.sleep(Duration.ofSeconds(2));
            future.join();

            assertEquals(1, postStub.getPostedQuestions().size());
            assertEquals("What does this function do?", postStub.getPostedQuestions().get(0));
            assertEquals(1, updateStub.getCallCount());
            assertEquals(1, approveStub.getCallCount());
        } finally {
            testEnv.close();
        }
    }

    @Test
    void multiTurnClarification_postsThreeQuestions_thenUpdatesAndApproves() {
        TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = testEnv.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(PRReviewWorkflowImpl.class);

            List<DocUpdate> updates = List.of(new DocUpdate("docs/api.md", "final content"));
            RecordingPostStub postStub = new RecordingPostStub();
            RecordingUpdateStub updateStub = new RecordingUpdateStub();
            RecordingApproveStub approveStub = new RecordingApproveStub();
            RecordingTimeoutStub timeoutStub = new RecordingTimeoutStub();

            worker.registerActivitiesImplementations(
                    new FixedScanStub(List.of("docs/api.md")),
                    new SequentialAnalyseStub(List.of(
                            new ReviewDecision.NeedsInput("Question 1?"),
                            new ReviewDecision.NeedsInput("Question 2?"),
                            new ReviewDecision.NeedsInput("Question 3?"),
                            new ReviewDecision.Autonomous(updates))),
                    postStub, updateStub, approveStub, timeoutStub);

            testEnv.start();
            WorkflowClient client = testEnv.getWorkflowClient();

            PRReviewWorkflow workflow = client.newWorkflowStub(
                    PRReviewWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("wf-multi-turn")
                            .setTaskQueue(TASK_QUEUE)
                            .build());

            CompletableFuture<Void> future = WorkflowClient.execute(workflow::start, createTestState());

            // Register callbacks for each signal at increasing delays
            testEnv.registerDelayedCallback(Duration.ofSeconds(1), () -> {
                PRReviewWorkflow s = client.newWorkflowStub(PRReviewWorkflow.class, "wf-multi-turn");
                s.developerReplySignal("Reply 1");
            });
            testEnv.registerDelayedCallback(Duration.ofSeconds(3), () -> {
                PRReviewWorkflow s = client.newWorkflowStub(PRReviewWorkflow.class, "wf-multi-turn");
                s.developerReplySignal("Reply 2");
            });
            testEnv.registerDelayedCallback(Duration.ofSeconds(5), () -> {
                PRReviewWorkflow s = client.newWorkflowStub(PRReviewWorkflow.class, "wf-multi-turn");
                s.developerReplySignal("Reply 3");
            });

            testEnv.sleep(Duration.ofSeconds(10));
            future.join();

            assertEquals(3, postStub.getPostedQuestions().size());
            assertEquals("Question 1?", postStub.getPostedQuestions().get(0));
            assertEquals("Question 2?", postStub.getPostedQuestions().get(1));
            assertEquals("Question 3?", postStub.getPostedQuestions().get(2));
            assertEquals(1, updateStub.getCallCount());
            assertEquals(1, approveStub.getCallCount());
        } finally {
            testEnv.close();
        }
    }

    // ========================================================================
    // Task 7.12: Timeout and failure path tests
    // ========================================================================

    @Test
    void timeoutOnFirstAwait_callsTimeoutActivity_doesNotUpdateOrApprove() {
        TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = testEnv.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(PRReviewWorkflowImpl.class);

            RecordingPostStub postStub = new RecordingPostStub();
            RecordingUpdateStub updateStub = new RecordingUpdateStub();
            RecordingApproveStub approveStub = new RecordingApproveStub();
            RecordingTimeoutStub timeoutStub = new RecordingTimeoutStub();

            worker.registerActivitiesImplementations(
                    new FixedScanStub(List.of("docs/api.md")),
                    new SequentialAnalyseStub(List.of(
                            new ReviewDecision.NeedsInput("What does this do?"))),
                    postStub, updateStub, approveStub, timeoutStub);

            testEnv.start();
            WorkflowClient client = testEnv.getWorkflowClient();

            PRReviewWorkflow workflow = client.newWorkflowStub(
                    PRReviewWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("wf-timeout")
                            .setTaskQueue(TASK_QUEUE)
                            .build());

            CompletableFuture<Void> future = WorkflowClient.execute(workflow::start, createTestState());

            // Don't send any signal — advance time past the 24-hour timeout
            testEnv.sleep(Duration.ofSeconds(86401));
            future.join();

            assertEquals(1, timeoutStub.getCallCount(), "TimeoutActivity should be called once");
            assertEquals("What does this do?", timeoutStub.getLastQuestions().get(0));
            assertEquals(0, updateStub.getCallCount(), "UpdateDocumentationActivity should NOT be called");
            assertEquals(0, approveStub.getCallCount(), "ApprovePRActivity should NOT be called");
        } finally {
            testEnv.close();
        }
    }

    @Test
    void activityFailure_workflowFailsAfterRetries() {
        TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = testEnv.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(PRReviewWorkflowImpl.class);

            RecordingUpdateStub updateStub = new RecordingUpdateStub();
            RecordingApproveStub approveStub = new RecordingApproveStub();
            RecordingTimeoutStub timeoutStub = new RecordingTimeoutStub();

            worker.registerActivitiesImplementations(
                    new FixedScanStub(List.of("docs/api.md")),
                    new SequentialAnalyseStub(List.of(
                            new ReviewDecision.NeedsInput("Question?"))),
                    new FailingPostStub(),
                    updateStub, approveStub, timeoutStub);

            testEnv.start();
            WorkflowClient client = testEnv.getWorkflowClient();

            PRReviewWorkflow workflow = client.newWorkflowStub(
                    PRReviewWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("wf-activity-failure")
                            .setTaskQueue(TASK_QUEUE)
                            .build());

            assertThrows(WorkflowFailedException.class, () -> {
                workflow.start(createTestState());
            });
        } finally {
            testEnv.close();
        }
    }

    // ========================================================================
    // Task 7.13: Conversation history correctness tests
    // ========================================================================

    @Test
    void conversationHistory_afterNTurns_analyseReceivesExactlyNEntriesInOrder() {
        int N = 3;
        TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = testEnv.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(PRReviewWorkflowImpl.class);

            List<DocUpdate> updates = List.of(new DocUpdate("docs/api.md", "final"));
            List<ReviewDecision> decisions = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                decisions.add(new ReviewDecision.NeedsInput("Question " + (i + 1) + "?"));
            }
            decisions.add(new ReviewDecision.Autonomous(updates));

            SequentialAnalyseStub analyseStub = new SequentialAnalyseStub(decisions);
            RecordingPostStub postStub = new RecordingPostStub();
            RecordingUpdateStub updateStub = new RecordingUpdateStub();
            RecordingApproveStub approveStub = new RecordingApproveStub();
            RecordingTimeoutStub timeoutStub = new RecordingTimeoutStub();

            worker.registerActivitiesImplementations(
                    new FixedScanStub(List.of("docs/api.md")),
                    analyseStub, postStub, updateStub, approveStub, timeoutStub);

            testEnv.start();
            WorkflowClient client = testEnv.getWorkflowClient();

            PRReviewWorkflow workflow = client.newWorkflowStub(
                    PRReviewWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("wf-history")
                            .setTaskQueue(TASK_QUEUE)
                            .build());

            CompletableFuture<Void> future = WorkflowClient.execute(workflow::start, createTestState());

            for (int i = 1; i <= N; i++) {
                final int replyNum = i;
                testEnv.registerDelayedCallback(Duration.ofSeconds(i * 2L), () -> {
                    PRReviewWorkflow s = client.newWorkflowStub(PRReviewWorkflow.class, "wf-history");
                    s.developerReplySignal("Reply " + replyNum);
                });
            }

            testEnv.sleep(Duration.ofSeconds(N * 2L + 2));
            future.join();

            List<List<ConversationTurn>> histories = analyseStub.getCapturedHistories();

            // N+1 analyse calls total
            assertEquals(N + 1, histories.size());

            // First call: empty history
            assertEquals(0, histories.get(0).size());

            // Subsequent calls: history grows by one each time
            for (int i = 1; i <= N; i++) {
                List<ConversationTurn> historyAtTurn = histories.get(i);
                assertEquals(i, historyAtTurn.size(),
                        "History at turn " + (i + 1) + " should have " + i + " entries");

                for (int j = 0; j < i; j++) {
                    ConversationTurn turn = historyAtTurn.get(j);
                    assertEquals("Question " + (j + 1) + "?", turn.question());
                    assertEquals("Reply " + (j + 1), turn.reply());
                }
            }
        } finally {
            testEnv.close();
        }
    }
}
