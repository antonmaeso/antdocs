package com.example.documenter.temporalworkflows.workflow;

import com.example.documenter.aigateway.domain.DocUpdate;
import com.example.documenter.aigateway.domain.RelevanceScanResult;
import com.example.documenter.aigateway.domain.ReviewDecision;
import com.example.documenter.temporalworkflows.activity.*;
import com.example.documenter.temporalworkflows.domain.ConversationTurn;
import com.example.documenter.temporalworkflows.domain.PRReviewState;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import net.jqwik.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PRReviewWorkflowPropertyTest {

    private static final String TQ = "pbt-queue";

    // Stubs
    static class FScan implements ScanRelevantDocsActivity {
        final List<String> p;
        FScan(List<String> p) { this.p = p; }
        public RelevanceScanResult scan(String d, String o, String r) {
            return new RelevanceScanResult(p);
        }
    }

    static class AutoAn implements AnalyseDiffActivity {
        final List<DocUpdate> u;
        AutoAn(List<DocUpdate> u) { this.u = u; }
        public ReviewDecision analyse(String d, List<String> p,
                List<ConversationTurn> h, String o, String r) {
            return new ReviewDecision.Autonomous(u);
        }
    }
}
