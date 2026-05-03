package com.example.documenter.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mockito;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.documentation.DocumentationRunEndpoint;
import com.example.documenter.prreviewer.PRCommentPoller;
import com.example.documenter.prreviewer.PRReviewer;
import com.example.documenter.prreviewer.WorkflowTracker;
import com.example.documenter.temporalworkflows.workflow.PRReviewWorkflow;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.FileDiff;
import com.example.documenter.vcsgateway.domain.PullRequest;
import com.example.documenter.vcsgateway.domain.PullRequestComment;
import com.example.documenter.vcsgateway.domain.PullRequestDiff;
import com.example.documenter.vcsgateway.domain.TreeEntry;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

/**
 * Integration tests that verify the full application wiring with mocked
 * external dependencies (VCSProvider, AIGateway, Temporal WorkflowClient).
 *
 * <p>Tests validate end-to-end flows:
 * <ul>
 *   <li>Documentation run: POST triggers crawl → AI generation → push to companion repo</li>
 *   <li>PR detection: polling detects new PR → starts Temporal workflow</li>
 *   <li>Comment polling: detects developer reply → sends signal to workflow</li>
 * </ul>
 */
@SpringJUnitConfig(IntegrationTestConfig.class)
class DocumenterIntegrationTest {

    @Autowired
    private VCSProvider vcsProvider;

    @Autowired
    private AIGateway aiGateway;

    @Autowired
    private WorkflowClient workflowClient;

    @Autowired
    private DocumentationRunEndpoint documentationRunEndpoint;

    @Autowired
    private PRReviewer prReviewer;

    @Autowired
    private PRCommentPoller prCommentPoller;

    @Autowired
    private WorkflowTracker workflowTracker;

    @Nested
    @DisplayName("Documentation Run Integration")
    class DocumentationRunIntegration {

        @BeforeEach
        void setUp() {
            Mockito.reset(vcsProvider, aiGateway);
        }

        @Test
        @DisplayName("POST /api/documentation/run → crawl two files → AI generates docs → pushFile called for each + README; status returns COMPLETED")
        void documentationRun_fullPipeline() throws InterruptedException {
            // Arrange: crawler returns two files
            when(vcsProvider.fetchFileTree("test-owner", "test-repo", "main"))
                    .thenReturn(List.of(
                            new TreeEntry("src/Main.java", "blob", "sha1"),
                            new TreeEntry("src/Utils.java", "blob", "sha2")
                    ));
            when(vcsProvider.fetchFileContent("test-owner", "test-repo", "src/Main.java", "main"))
                    .thenReturn("public class Main { void run() {} }");
            when(vcsProvider.fetchFileContent("test-owner", "test-repo", "src/Utils.java", "main"))
                    .thenReturn("public class Utils { static String format() { return \"\"; } }");

            // AI generates documentation for each file
            when(aiGateway.generateDocumentation(anyString(), eq("src/Main.java")))
                    .thenReturn("# Main\nMain class that runs the application.");
            when(aiGateway.generateDocumentation(anyString(), eq("src/Utils.java")))
                    .thenReturn("# Utils\nUtility class with formatting helpers.");
            when(aiGateway.generateRepositoryReadme(anyList(), eq("test-repo")))
                    .thenReturn("# test-repo\nA repository with Main and Utils classes.");

            // Companion repo already exists (fetchFileTree does not throw 404)
            when(vcsProvider.fetchFileTree("test-owner", "test-repo_ai_documentation", "main"))
                    .thenReturn(List.of());

            // Act: trigger the documentation run
            ResponseEntity<Map<String, String>> response = documentationRunEndpoint.startRun();

            // Assert: HTTP 202 Accepted with runId
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isNotNull();
            String runId = response.getBody().get("runId");
            assertThat(runId).isNotNull().isNotBlank();

            // Wait for async pipeline to complete
            pollUntilCompleted(runId, Duration.ofSeconds(10));

            // Verify: status endpoint returns COMPLETED
            ResponseEntity<Map<String, String>> statusResponse = documentationRunEndpoint.getRunStatus(runId);
            assertThat(statusResponse.getBody()).isNotNull();
            assertThat(statusResponse.getBody().get("status")).isEqualTo("COMPLETED");

            // Verify: pushFile called for both source file docs
            verify(vcsProvider, atLeastOnce()).pushFile(
                    eq("test-owner"), eq("test-repo_ai_documentation"),
                    eq("src/Main.java.md"), anyString(), anyString());
            verify(vcsProvider, atLeastOnce()).pushFile(
                    eq("test-owner"), eq("test-repo_ai_documentation"),
                    eq("src/Utils.java.md"), anyString(), anyString());

            // Verify: pushFile called for README
            verify(vcsProvider, atLeastOnce()).pushFile(
                    eq("test-owner"), eq("test-repo_ai_documentation"),
                    eq("README.md"), anyString(), anyString());
        }

        private void pollUntilCompleted(String runId, Duration timeout) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                ResponseEntity<Map<String, String>> status = documentationRunEndpoint.getRunStatus(runId);
                if (status.getBody() != null && !"IN_PROGRESS".equals(status.getBody().get("status"))) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Documentation run did not complete within " + timeout);
        }
    }

    @Nested
    @DisplayName("PR Detection Integration")
    class PRDetectionIntegration {

        @BeforeEach
        void setUp() {
            Mockito.reset(vcsProvider, workflowClient);
            // Clear the workflow tracker
            workflowTracker.allTracked().forEach(pr ->
                    workflowTracker.remove(pr.owner(), pr.repo(), pr.prNumber()));
        }

        @Test
        @DisplayName("PRReviewer detects new PR → starts workflow with correct ID → WorkflowTracker has entry")
        void prDetection_startsWorkflowForNewPR() {
            // Arrange: one new open PR
            PullRequest newPR = new PullRequest(42, "Add feature X", "head-sha", "base-sha");
            when(vcsProvider.listOpenPullRequests("test-owner", "test-repo"))
                    .thenReturn(List.of(newPR));
            when(vcsProvider.fetchPullRequestDiff("test-owner", "test-repo", 42))
                    .thenReturn(new PullRequestDiff(42, List.of(
                            new FileDiff("src/Feature.java", "@@ -1,3 +1,5 @@\n+new code")
                    )));

            // Mock WorkflowClient.newWorkflowStub to return a mock workflow
            PRReviewWorkflow mockWorkflow = mock(PRReviewWorkflow.class);
            when(workflowClient.newWorkflowStub(eq(PRReviewWorkflow.class), any(WorkflowOptions.class)))
                    .thenReturn(mockWorkflow);

            // Act: trigger the polling method directly
            prReviewer.pollForNewPRs();

            // Assert: workflow stub created with correct options
            ArgumentCaptor<WorkflowOptions> optionsCaptor = ArgumentCaptor.forClass(WorkflowOptions.class);
            verify(workflowClient).newWorkflowStub(eq(PRReviewWorkflow.class), optionsCaptor.capture());

            WorkflowOptions capturedOptions = optionsCaptor.getValue();
            assertThat(capturedOptions.getWorkflowId())
                    .isEqualTo("pr-review-test-owner-test-repo-42");
            assertThat(capturedOptions.getTaskQueue())
                    .isEqualTo("pr-review-queue");

            // Assert: WorkflowTracker has the entry
            assertThat(workflowTracker.findWorkflowId("test-owner", "test-repo", 42))
                    .isPresent()
                    .hasValue("pr-review-test-owner-test-repo-42");
        }
    }

    @Nested
    @DisplayName("Comment Polling Integration")
    class CommentPollingIntegration {

        @BeforeEach
        void setUp() {
            Mockito.reset(vcsProvider, workflowClient);
            // Clear the workflow tracker
            workflowTracker.allTracked().forEach(pr ->
                    workflowTracker.remove(pr.owner(), pr.repo(), pr.prNumber()));
        }

        @Test
        @DisplayName("PRCommentPoller detects new non-bot comment → sends developerReplySignal to correct workflow")
        void commentPolling_sendsSignalForNewDeveloperReply() {
            // Arrange: seed WorkflowTracker with one tracked PR
            String workflowId = "pr-review-test-owner-test-repo-7";
            workflowTracker.register("test-owner", "test-repo", 7, workflowId);

            // Mock: one new non-bot comment (ID > 0 since lastSeenCommentId starts at 0)
            PullRequestComment devComment = new PullRequestComment(
                    "Here is my explanation of the change", null, 0, "developer-user", 101L);
            when(vcsProvider.listPullRequestComments("test-owner", "test-repo", 7))
                    .thenReturn(List.of(devComment));

            // Mock: workflow stub for signal delivery
            PRReviewWorkflow mockWorkflow = mock(PRReviewWorkflow.class);
            when(workflowClient.newWorkflowStub(PRReviewWorkflow.class, workflowId))
                    .thenReturn(mockWorkflow);

            // Act: trigger the polling method directly
            prCommentPoller.pollForNewComments();

            // Assert: developerReplySignal sent with correct comment body
            verify(mockWorkflow).developerReplySignal("Here is my explanation of the change");

            // Assert: lastSeenCommentId updated in tracker
            assertThat(workflowTracker.findTrackedPR("test-owner", "test-repo", 7))
                    .isPresent()
                    .hasValueSatisfying(tracked ->
                            assertThat(tracked.lastSeenCommentId()).isEqualTo(101L));
        }
    }
}
