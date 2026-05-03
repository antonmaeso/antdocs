package com.example.documenter.documentation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.documenter.crawler.CrawledFile;
import com.example.documenter.crawler.RepositoryCrawler;
import com.example.documenter.documentation.domain.DocumentationArtifact;
import com.example.documenter.documentation.domain.DocumentationRun;
import com.example.documenter.documentation.domain.RunStatus;

/**
 * REST controller for triggering and monitoring documentation generation runs.
 *
 * <p>{@code POST /api/documentation/run} starts an async pipeline (crawl → generate → store)
 * and returns HTTP 202 with a {@code runId}. {@code GET /api/documentation/run/{runId}/status}
 * returns the current status and summary.
 */
@RestController
@RequestMapping("/api/documentation")
public class DocumentationRunEndpoint {

    private static final Logger log = LoggerFactory.getLogger(DocumentationRunEndpoint.class);

    private final RepositoryCrawler crawler;
    private final DocumentationGenerator generator;
    private final DocumentationStorage storage;
    private final String owner;
    private final String repo;
    private final String ref;
    private final AtomicReference<DocumentationRun> currentRun = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public DocumentationRunEndpoint(RepositoryCrawler crawler,
                                     DocumentationGenerator generator,
                                     DocumentationStorage storage,
                                     @Value("${target-repository.owner}") String targetOwner,
                                     @Value("${target-repository.repo}") String targetRepo,
                                     @Value("${target-repository.ref}") String targetRef) {
        this.crawler = crawler;
        this.generator = generator;
        this.storage = storage;
        this.owner = targetOwner;
        this.repo = targetRepo;
        this.ref = targetRef;
    }

    /**
     * Starts a new documentation generation run.
     *
     * @return 202 with runId, 409 if already in progress, 400 if config invalid
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> startRun() {
        // Validate configuration
        if (isBlank(owner) || isBlank(repo)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Target repository configuration is missing or invalid"));
        }

        // Check for concurrent run
        DocumentationRun existing = currentRun.get();
        if (existing != null && existing.status() == RunStatus.IN_PROGRESS) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A documentation run is already in progress",
                                 "runId", existing.runId()));
        }

        String runId = UUID.randomUUID().toString();
        DocumentationRun run = new DocumentationRun(
                runId, owner, repo, Instant.now(), RunStatus.IN_PROGRESS, "Documentation run started");
        currentRun.set(run);

        executor.submit(() -> executeRun(runId));

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("runId", runId));
    }

    /**
     * Returns the status of a documentation run.
     *
     * @param runId the run identifier
     * @return the run status and summary, or 404 if unknown
     */
    @GetMapping("/run/{runId}/status")
    public ResponseEntity<Map<String, String>> getRunStatus(@PathVariable String runId) {
        DocumentationRun run = currentRun.get();
        if (run == null || !run.runId().equals(runId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Unknown run ID: " + runId));
        }

        return ResponseEntity.ok(Map.of(
                "runId", run.runId(),
                "status", run.status().name(),
                "summary", run.summary()));
    }

    private void executeRun(String runId) {
        try {
            List<CrawledFile> files = crawler.crawl(owner, repo, ref);
            List<DocumentationArtifact> artifacts = generator.generate(files, repo);
            storage.store(artifacts, owner, repo);

            updateRun(runId, RunStatus.COMPLETED,
                    "Documentation run completed: " + artifacts.size() + " artifacts generated");
        } catch (Exception e) {
            log.error("Documentation run {} failed: {}", runId, e.getMessage(), e);
            updateRun(runId, RunStatus.FAILED, "Documentation run failed: " + e.getMessage());
        }
    }

    private void updateRun(String runId, RunStatus status, String summary) {
        DocumentationRun current = currentRun.get();
        if (current != null && current.runId().equals(runId)) {
            currentRun.set(new DocumentationRun(
                    runId, current.owner(), current.repo(), current.startedAt(), status, summary));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
