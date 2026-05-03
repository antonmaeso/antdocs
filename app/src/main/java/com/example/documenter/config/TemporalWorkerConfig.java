package com.example.documenter.config;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.temporalworkflows.activity.impl.AnalyseDiffActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.ApprovePRActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.PostCommentActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.ScanRelevantDocsActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.TimeoutActivityImpl;
import com.example.documenter.temporalworkflows.activity.impl.UpdateDocumentationActivityImpl;
import com.example.documenter.temporalworkflows.config.TemporalProperties;
import com.example.documenter.temporalworkflows.workflow.PRReviewWorkflowImpl;
import com.example.documenter.vcsgateway.VCSProvider;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * Configures the Temporal client, worker factory, and registers the PR review
 * workflow implementation along with all six activity implementations on the
 * configured task queue.
 */
@Configuration
public class TemporalWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerConfig.class);
    private static final String TASK_QUEUE = "pr-review-queue";

    private WorkerFactory workerFactory;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs(TemporalProperties temporalProperties) {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalProperties.getHost() + ":" + temporalProperties.getPort())
                .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs,
                                          TemporalProperties temporalProperties) {
        WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
                .setNamespace(temporalProperties.getNamespace())
                .build();
        return WorkflowClient.newInstance(serviceStubs, options);
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient workflowClient,
                                       VCSProvider vcsProvider,
                                       AIGateway aiGateway) {
        this.workerFactory = WorkerFactory.newInstance(workflowClient);

        Worker worker = workerFactory.newWorker(TASK_QUEUE);

        // Register workflow implementation
        worker.registerWorkflowImplementationTypes(PRReviewWorkflowImpl.class);

        // Register all six activity implementations
        worker.registerActivitiesImplementations(
                new ScanRelevantDocsActivityImpl(vcsProvider, aiGateway),
                new AnalyseDiffActivityImpl(vcsProvider, aiGateway),
                new PostCommentActivityImpl(vcsProvider),
                new UpdateDocumentationActivityImpl(vcsProvider),
                new ApprovePRActivityImpl(vcsProvider),
                new TimeoutActivityImpl(vcsProvider)
        );

        workerFactory.start();
        log.info("Temporal worker started on task queue: {}", TASK_QUEUE);

        return workerFactory;
    }

    @PreDestroy
    public void shutdown() {
        if (workerFactory != null) {
            workerFactory.shutdown();
            log.info("Temporal worker factory shut down");
        }
    }
}
