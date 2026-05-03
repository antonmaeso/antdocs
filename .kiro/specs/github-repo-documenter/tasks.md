# Task List: GitHub Repo Documenter

Tasks are ordered by dependency — each group can only begin once the groups it depends on are complete.

---

## Phase 1: Project Scaffold

- [x] 1.1 Initialise Spring Boot multi-module Maven/Gradle project with modules: `app`, `crawler`, `documentation`, `pr-reviewer`, `ai-gateway`, `vcs-gateway`, `temporal-workflows`
- [x] 1.2 Add `application.yml` with all configuration properties defined in the design (vcs, target-repository, crawler, spring.ai, temporal, polling)
- [x] 1.3 Add `application-test.yml` with test-safe overrides (in-memory mocks, short timeouts, test bot-username)
- [x] 1.4 Add Docker Compose file for Temporal server (server + UI + PostgreSQL persistence); verify `temporal-ui` is reachable at `localhost:8080` after `docker compose up`
- [x] 1.5 Configure module dependency rules in build file — enforce that no module depends on a concrete provider class; add an ArchUnit test that asserts this at build time
- [x] 1.6 Add `spring-boot-configuration-processor` and define `@ConfigurationProperties` binding classes for each config namespace (`VcsProperties`, `TemporalProperties`, `CrawlerProperties`, `PollingProperties`) so all config is type-safe and validated at startup

---

## Phase 2: Domain Records and Shared Types

- [x] 2.1 Define all VCS domain records: `TreeEntry`, `PullRequest`, `PullRequestDiff`, `FileDiff`, `PullRequestComment`
- [x] 2.2 Define all documentation domain records: `DocumentationArtifact`, `DocumentationRun`, `RunStatus`, `DocFileSummary`, `DocFileContent`, `DocUpdate`
- [x] 2.3 Define AI gateway domain types: `ReviewDecision` sealed interface with `Autonomous` and `NeedsInput` variants, `RelevanceScanResult`
- [x] 2.4 Define workflow domain records: `ConversationTurn`, `PRReviewState`, `TrackedPR`

---

## Phase 3: VCS Gateway Module

- [x] 3.1 Define `VCSProvider` interface with all nine methods; add Javadoc on each method specifying the expected behaviour on error (what is thrown vs swallowed)
- [x] 3.2 Define a shared `RateLimitException` (unchecked) and a `RateLimitHandler` utility that reads a provider-supplied `retryAfterSeconds` value, sleeps, and retries — used internally by all three providers to avoid duplicating sleep/retry logic
- [x] 3.3 Implement `GitHubProvider` — read operations:
  - `fetchFileTree`: call `GET /repos/{owner}/{repo}/git/trees/{ref}?recursive=1`; map `tree[]` entries to `TreeEntry`
  - `fetchFileContent`: call `GET /repos/{owner}/{repo}/contents/{path}`; base64-decode the `content` field
  - `listOpenPullRequests`: call `GET /repos/{owner}/{repo}/pulls?state=open`; map to `PullRequest`
  - `fetchPullRequestDiff`: call `GET /repos/{owner}/{repo}/pulls/{prNumber}` with `Accept: application/vnd.github.diff`; parse unified diff into `FileDiff` list
  - `listPullRequestComments`: call `GET /repos/{owner}/{repo}/issues/{prNumber}/comments`; map to `PullRequestComment` including `id` field
- [x] 3.4 Implement `GitHubProvider` — write operations:
  - `postPullRequestComment`: call `POST /repos/{owner}/{repo}/issues/{prNumber}/comments`
  - `approvePullRequest`: call `POST /repos/{owner}/{repo}/pulls/{prNumber}/reviews` with `event: APPROVE`
  - `createRepository`: call `POST /orgs/{owner}/repos` (org) or `POST /user/repos` (personal); handle 422 if repo already exists
  - `pushFile`: call `PUT /repos/{owner}/{repo}/contents/{path}`; fetch existing file SHA first if file exists (required by GitHub API for updates)
- [x] 3.5 Implement `GitHubProvider` — rate-limit and auth:
  - On HTTP 429: read `X-RateLimit-Reset` header; delegate to `RateLimitHandler`; attach `Authorization: Bearer {token}` and `X-GitHub-Api-Version` headers to every request
- [x] 3.6 Implement `GitLabProvider` — read operations:
  - `fetchFileTree`: call `GET /projects/{id}/repository/tree?recursive=true&ref={ref}`; paginate; map to `TreeEntry`
  - `fetchFileContent`: call `GET /projects/{id}/repository/files/{path}/raw?ref={ref}`
  - `listOpenPullRequests`: call `GET /projects/{id}/merge_requests?state=opened`; map to `PullRequest`
  - `fetchPullRequestDiff`: call `GET /projects/{id}/merge_requests/{iid}/diffs`; map to `FileDiff` list
  - `listPullRequestComments`: call `GET /projects/{id}/merge_requests/{iid}/notes`; map to `PullRequestComment`
- [x] 3.7 Implement `GitLabProvider` — write operations and rate-limit:
  - `postPullRequestComment`: `POST /projects/{id}/merge_requests/{iid}/notes`
  - `approvePullRequest`: `POST /projects/{id}/merge_requests/{iid}/approve`
  - `createRepository`: `POST /projects` with `namespace_id` for group repos
  - `pushFile`: `POST /projects/{id}/repository/files/{path}` (create) or `PUT` (update); check existence first
  - On HTTP 429: read `RateLimit-Reset` header; delegate to `RateLimitHandler`; attach `PRIVATE-TOKEN: {token}` header to every request
- [x] 3.8 Implement `BitbucketProvider` — read operations:
  - `fetchFileTree`: call `GET /repositories/{workspace}/{repo}/src/{ref}/`; paginate through `next` links; map to `TreeEntry`
  - `fetchFileContent`: call `GET /repositories/{workspace}/{repo}/src/{ref}/{path}`
  - `listOpenPullRequests`: call `GET /repositories/{workspace}/{repo}/pullrequests?state=OPEN`; paginate
  - `fetchPullRequestDiff`: call `GET /repositories/{workspace}/{repo}/pullrequests/{id}/diff`
  - `listPullRequestComments`: call `GET /repositories/{workspace}/{repo}/pullrequests/{id}/comments`; paginate
- [x] 3.9 Implement `BitbucketProvider` — write operations and rate-limit:
  - `postPullRequestComment`: `POST /repositories/{workspace}/{repo}/pullrequests/{id}/comments`
  - `approvePullRequest`: `POST /repositories/{workspace}/{repo}/pullrequests/{id}/approve`
  - `createRepository`: `POST /repositories/{workspace}/{repo}`
  - `pushFile`: use Bitbucket `src` form-data upload endpoint `POST /repositories/{workspace}/{repo}/src`
  - On HTTP 429: read `Retry-After` header; delegate to `RateLimitHandler`; attach `Authorization: Bearer {token}` header to every request
- [x] 3.10 Implement `VCSProviderFactory` bean: reads `vcs.provider`, instantiates the correct implementation, fails fast on invalid or missing value
- [x] 3.11 Add startup validation: fail fast if selected provider's PAT is missing; fail fast if `vcs.bot-username` is missing
- [x] 3.12 Write unit tests for `GitHubProvider` read operations: mock HTTP responses for all five read methods; verify correct domain object field mapping including edge cases (empty tree, PR with no files changed, comment with no line reference)
- [x] 3.13 Write unit tests for `GitHubProvider` write operations: verify correct HTTP method, URL, and request body for all four write methods; verify SHA pre-fetch on `pushFile` update
- [x] 3.14 Write unit tests for `GitHubProvider` rate-limit: mock 429 response with `X-RateLimit-Reset`; verify sleep duration calculated correctly; verify request retried exactly once after sleep; verify PAT and version headers present on every request
- [x] 3.15 Write unit tests for `GitLabProvider`: same three-part coverage as 3.12–3.14 for GitLab-specific field names and endpoints
- [x] 3.16 Write unit tests for `BitbucketProvider`: same three-part coverage as 3.12–3.14 including pagination (multi-page responses for file tree and comments)
- [x] 3.17 Write unit tests for `VCSProviderFactory`: correct provider for each valid `vcs.provider` value; startup failure for invalid value; startup failure for missing PAT; startup failure for missing `bot-username`
- [x] 3.18 Write property tests `VCSProviderPropertyTest`: `apiResponsesMappedCorrectlyToDomainObjects` (Property 18), `allRequestsCarryConfiguredPAT` (Property 19)

---

## Phase 4: AI Gateway Module

- [x] 4.1 Define `AIGateway` interface with all five methods; add Javadoc specifying the expected input variables for each prompt template
- [x] 4.2 Implement `SpringAIGateway` — infrastructure wiring:
  - Configure `ChatClient` bean with Ollama via `spring.ai.ollama.*` properties
  - Implement a `PromptLoader` helper that loads a `.st` file from the classpath, substitutes named variables, and returns the rendered string — used by all five methods
  - Add startup validation: verify all five `.st` files are present on the classpath at application start; fail fast with the missing filename if any are absent
- [x] 4.3 Implement remaining `SpringAIGateway` methods:
  - `generateDocumentation()`: load `generate-documentation.st`; variables: `{filePath}`, `{fileContent}`; parse response to extract `markdownContent` and `shortDescription`
  - `generateRepositoryReadme()`: load `generate-readme.st`; variables: `{repoName}`, `{fileSummaries}`
  - `scanRelevantDocs()`: load `scan-relevant-docs.st`; variables: `{diffPatch}`, `{docSummaries}`; parse response to `RelevanceScanResult`
  - `analyseAndDecide()`: load `analyse-diff.st`; variables: `{diffPatch}`, `{relevantDocs}`, `{history}`; parse response to `ReviewDecision`
  - `generateUpdatedDocContent()`: load `analyse-diff.st` or dedicated template; variables: `{originalContent}`, `{diffPatch}`, `{history}`
- [x] 4.4 Create prompt template files under `ai-gateway/src/main/resources/prompts/`: `generate-documentation.st`, `generate-readme.st`, `scan-relevant-docs.st`, `analyse-diff.st`, `pr-timeout-followup.st`
- [x] 4.5 Add startup validation: fail fast if `spring.ai.*` configuration is missing or invalid
- [x] 4.6 Write unit tests for `SpringAIGateway`: verify each method loads the correct template and passes the correct variables to the AI client
- [x] 4.7 Write unit tests for `PromptLoader`: verify each `.st` file loads and renders with representative variable maps; verify missing variable throws a descriptive exception
- [x] 4.8 Write unit tests for startup validation: verify application fails to start when any `.st` file is absent from classpath
- [x] 4.9 Write unit tests for all five prompt templates: verify each `.st` file is loadable from classpath and renders without error with representative variable maps

---

## Phase 5: Crawler Module

- [x] 5.1 Implement extension filter logic: given `include-extensions` and `exclude-extensions` lists, produce a `Predicate<String>` that accepts a file path — include list takes precedence; empty include list means accept all; exclude list always applied after include
- [x] 5.2 Implement `RepositoryCrawler.crawl()`:
  - Call `VCSProvider.fetchFileTree()`
  - For each entry: skip if `type == "commit"` (submodule) and log warning; skip if extension filter rejects path
  - For each accepted blob: call `VCSProvider.fetchFileContent()`; on exception log error with path and continue
  - Return list of `(path, content)` pairs for all successfully fetched files
- [x] 5.3 Write unit tests: rate-limit delegation (verify VCSProvider called, not Crawler); submodule skip with warning log; include-only filter; exclude-only filter; partial fetch failure continues
- [x] 5.4 Write property tests `CrawlerPropertyTest`: `crawlerFetchesContentForEveryBlob` (P1), `partialFailuresDoNotAbortCrawl` (P2), `extensionFilterIsRespected` (P3), `submoduleEntriesAreNeverIncluded` (P4)

---

## Phase 6: Documentation Module

- [x] 6.1 Implement `DocumentationGenerator.generate()`:
  - For each `(path, content)` pair from the Crawler, call `AIGateway.generateDocumentation()`
  - On failure: retry up to 3× with exponential backoff (1s, 2s, 4s) using a `RetryTemplate` or manual loop; after 3 failures log and skip the file
  - Collect successful results as `DocumentationArtifact` list (each with `sourcePath`, `markdownContent`, `shortDescription`)
  - After all files processed, collect `shortDescription` values and call `AIGateway.generateRepositoryReadme()`; produce a `DocumentationArtifact` for the README at path `README.md`
- [x] 6.2 Implement `DocumentationStorage.store()`:
  - Check if companion repo exists via `VCSProvider.fetchFileTree()`; if not found (404), call `VCSProvider.createRepository()`
  - For each `DocumentationArtifact`, derive target path as `{sourcePath}.md`; call `VCSProvider.pushFile()` with a standard commit message
  - On push failure: log error with path and HTTP status; continue to next artifact
- [x] 6.3 Implement `DocumentationRunEndpoint`:
  - `POST /api/documentation/run`: check `AtomicReference<DocumentationRun>` — if `IN_PROGRESS` return 409; if config invalid return 400; otherwise create a new `DocumentationRun` with a UUID `runId`, set status to `IN_PROGRESS`, store in reference, submit async task, return 202 with `runId`
  - `GET /api/documentation/run/{runId}/status`: return current `RunStatus` and summary message
- [x] 6.4 Write unit tests for `DocumentationGenerator`: retry on AI failure (3 failures → skip + log); README called after all files; `shortDescription` populated
- [x] 6.5 Write unit tests for `DocumentationStorage`: repo creation when absent; push failure logged with path and HTTP status; round-trip byte equivalence test
- [x] 6.6 Write unit tests for `DocumentationRunEndpoint`: 202 + runId on valid config; 400 on missing config; 409 on concurrent call; status endpoint returns correct states
- [x] 6.7 Write property tests `DocumentationGeneratorPropertyTest`: `documentationGeneratedForEveryFile` (P5), `generatedOutputIsNonEmptyMarkdownWithDescription` (P6)
- [x] 6.8 Write property tests `DocumentationStoragePropertyTest`: `allArtifactsArePushed` (P7), `directoryStructureIsMirrored` (P8), `pushIsIdempotent` (P9)

---

## Phase 7: Temporal Workflows Module

- [x] 7.1 Define `PRReviewWorkflow` interface: `@WorkflowInterface` with `start(PRReviewState)` method and `@SignalMethod developerReplySignal(String commentBody)`
- [x] 7.2 Implement `PRReviewWorkflowImpl` — workflow state and signal handling:
  - Declare mutable workflow state: `List<ConversationTurn> history`, `String pendingReply`, `boolean replyReceived`
  - Implement `developerReplySignal()`: set `pendingReply = commentBody`, `replyReceived = true`
  - On each `Workflow.await(timeout, () -> replyReceived)` call: reset `replyReceived = false` after consuming the reply
- [x] 7.3 Implement `PRReviewWorkflowImpl` — main execution loop:
  - Step 1: execute `ScanRelevantDocsActivity`; store relevant paths in workflow state
  - Step 2: execute `AnalyseDiffActivity` with diff, relevant doc contents, and current `history`
  - Step 3a: if `ReviewDecision.Autonomous` — execute `UpdateDocumentationActivity`, then `ApprovePRActivity`, complete
  - Step 3b: if `ReviewDecision.NeedsInput` — execute `PostCommentActivity` with question; call `Workflow.await(signalTimeout, () -> replyReceived)`; if timed out execute `TimeoutActivity` and complete; if reply received append `ConversationTurn(question, reply)` to `history` and go to Step 2
- [x] 7.4 Implement `ScanRelevantDocsActivity`:
  - Fetch doc file tree from DocumentationRepository via `VCSProvider.fetchFileTree()`
  - For each blob, fetch content and extract `shortDescription` (first line after a `<!-- description:` marker, or first sentence of content)
  - Build `List<DocFileSummary>`; call `AIGateway.scanRelevantDocs(diffPatch, summaries)`; return `RelevanceScanResult.relevantPaths`
- [x] 7.5 Implement `AnalyseDiffActivity`:
  - Fetch full content of each relevant doc file via `VCSProvider.fetchFileContent()`
  - Build `List<DocFileContent>`; call `AIGateway.analyseAndDecide(diffPatch, relevantDocs, history)`; return `ReviewDecision`
- [x] 7.6 Implement `PostCommentActivity`: post question text to PR via `VCSProvider.postPullRequestComment()`
- [x] 7.7 Implement `UpdateDocumentationActivity`: call `VCSProvider.pushFile()` for each `DocUpdate` in the list
- [x] 7.8 Implement `ApprovePRActivity`: call `VCSProvider.approvePullRequest()` with PR coordinates
- [x] 7.9 Implement `TimeoutActivity`: load `pr-timeout-followup.st` via `AIGateway`; post timeout follow-up comment via `VCSProvider.postPullRequestComment()`
- [x] 7.10 Configure default retry policy on all activities: initial interval 1s, backoff coefficient 2.0, max attempts 3
- [x] 7.11 Write workflow tests using `TestWorkflowEnvironment`:
  - Autonomous path: mocked ReviewAgent returns `Autonomous` → `UpdateDocumentationActivity` + `ApprovePRActivity` called, no questions posted
  - Single-turn clarification: `NeedsInput` then `Autonomous` → one question posted, one signal sent, then docs updated and PR approved
  - Multi-turn (N=3): mock returns `NeedsInput` × 3 then `Autonomous`; send three signals; verify three questions posted in order, then docs updated and PR approved
- [x] 7.12 Write workflow tests — timeout and failure paths:
  - Timeout on first await: advance `TestWorkflowEnvironment` clock past `signal-timeout-seconds`; verify `TimeoutActivity` called; verify `UpdateDocumentationActivity` and `ApprovePRActivity` NOT called
  - Timeout on second await (after one reply received): verify `TimeoutActivity` called with the second question as `lastQuestion`
  - Activity failure exhausts retries: mock `PostCommentActivity` to always throw; verify workflow marked failed after 3 attempts
- [x] 7.13 Write workflow tests — conversation history correctness:
  - After N turns, verify `AnalyseDiffActivity` on turn N+1 receives a history list of exactly N `ConversationTurn` entries in order
- [x] 7.14 Write activity unit tests (mocked `VCSProvider` and `AIGateway`) for all six activities: verify correct method called on mock, correct arguments passed, correct return value propagated
- [x] 7.15 Write property tests `PRReviewWorkflowPropertyTest`: `autonomousCompletionWhenAgentSatisfiedImmediately` (P12), `multiTurnConversationUntilAgentSatisfied` (P13), `conversationHistoryIsCompleteAndOrdered` (P14), `workflowStartIsIdempotent` (P17)

---

## Phase 8: PR Reviewer Module

- [x] 8.1 Implement `WorkflowTracker`:
  - Backing store: `ConcurrentHashMap<String, TrackedPR>` keyed by `"{owner}/{repo}/pr/{prNumber}"`
  - `register()`: put entry; if key already exists, overwrite (idempotent)
  - `findWorkflowId()`: return `Optional<String>` of `TrackedPR.workflowId`
  - `remove()`: remove entry; no-op if not present
  - `allTracked()`: return unmodifiable snapshot of current entries
  - `updateLastSeenCommentId()`: atomically update `lastSeenCommentId` on the `TrackedPR` for a given key
- [x] 8.2 Implement `PRReviewer` — PR detection loop:
  - `@Scheduled(fixedDelayString = "${polling.interval-seconds}000")` method
  - Call `VCSProvider.listOpenPullRequests()`; on exception log and return
  - For each open PR: if `WorkflowTracker.findWorkflowId()` is present, skip
  - For new PRs: call `VCSProvider.fetchPullRequestDiff()`; build `PRReviewState`; start workflow via `WorkflowClient.newWorkflowStub()` with ID `pr-review-{owner}-{repo}-{prNumber}` and `WorkflowIdReusePolicy.REJECT_DUPLICATE`; register in `WorkflowTracker`
  - On PR close/merge: remove from `WorkflowTracker`
- [x] 8.3 Implement `PRCommentPoller`:
  - `@Scheduled(fixedDelayString = "${polling.interval-seconds}000")` method
  - For each PR in `WorkflowTracker`, call `VCSProvider.listPullRequestComments()`
  - Filter: `author != vcs.bot-username` and `id > lastSeenCommentId`
  - For each qualifying comment: get workflow stub via `WorkflowClient.newWorkflowStub(workflowId)`; call `developerReplySignal(comment.body())`; on `WorkflowNotFoundException` or completed workflow exception: log warning and call `WorkflowTracker.remove()`
  - After processing all qualifying comments for a PR: update `lastSeenCommentId` to the max comment ID seen in this cycle
- [x] 8.4 Write unit tests for `WorkflowTracker`: register new entry; register duplicate (idempotent); find present entry; find absent entry returns empty; remove present entry; remove absent entry is no-op; `allTracked()` returns snapshot not live view; `updateLastSeenCommentId()` updates correctly; concurrent register+remove does not throw
- [x] 8.5 Write unit tests for `PRReviewer`: new PR not in tracker → workflow started and registered; PR already in tracker → workflow not started; VCS error on list PRs → cycle skipped, tracker unchanged; PR closed → removed from tracker; multiple new PRs → one workflow per PR
- [x] 8.6 Write unit tests for `PRCommentPoller`: new developer reply (non-bot, new ID) → signal sent, `lastSeenCommentId` updated; bot comment → no signal; already-seen comment (ID ≤ lastSeen) → no signal; multiple new replies in one cycle → signals sent in order; signal to completed workflow → warning logged + PR removed from tracker; VCS error on list comments → log and continue to next PR
- [x] 8.7 Write property tests `PRReviewerPropertyTest`: `workflowStartedForEveryUntrackedPR` (P10)
- [x] 8.8 Write property tests `WorkflowTrackerPropertyTest`: `trackerRegistersAndDeregistersCorrectly` (P11)
- [x] 8.9 Write property tests `PRCommentPollerPropertyTest`: `signalSentForEveryNewDeveloperReply` (P15), `noDuplicateSignalsOnNoNewComments` (P16)

---

## Phase 9: Integration and End-to-End Wiring

- [x] 9.1 Wire the `app` module:
  - Spring Boot main class with `@SpringBootApplication` and explicit `scanBasePackages` for each module
  - Register Temporal worker: create `WorkerFactory`, register `PRReviewWorkflowImpl` and all six activity implementations on the configured task queue
  - Expose `VCSProviderFactory` as a primary bean; inject into all modules via the `VCSProvider` interface
  - Expose `SpringAIGateway` as a primary bean; inject into all modules via the `AIGateway` interface
  - Ensure `PRReviewer` and `PRCommentPoller` `@Scheduled` methods are active (enable scheduling via `@EnableScheduling`)
- [x] 9.2 Verify all fail-fast startup validations fire in isolation: write a `@SpringBootTest` per invalid config scenario (invalid provider, missing PAT, missing bot-username, missing AI config, missing `.st` file) and assert the context fails to load with the expected error message
- [x] 9.3 Write `@SpringBootTest` integration tests with `@MockBean` for `VCSProvider`, `AIGateway`, and Temporal client:
  - Documentation run: `POST /api/documentation/run` → mock crawler returns two files → mock AI returns docs → verify `VCSProvider.pushFile()` called twice + README push; status endpoint returns `COMPLETED`
  - PR detection: trigger `PRReviewer` scheduled method → mock returns one new PR → verify Temporal `WorkflowClient.start()` called with correct workflow ID → verify `WorkflowTracker` has entry
  - Comment polling: seed `WorkflowTracker` with one PR → mock returns one new non-bot comment → trigger `PRCommentPoller` → verify `developerReplySignal` sent to correct workflow stub
- [x] 9.4 Manual smoke test checklist (document as a runbook in `docs/smoke-test.md`):
  - Start Temporal via `docker compose up`
  - Start application with Ollama running locally
  - Call `POST /api/documentation/run`; poll status until `COMPLETED`; verify companion repo created with Markdown files
  - Raise a test PR on the target repo; wait one polling cycle; verify workflow appears in Temporal UI
  - Reply to the bot's comment on the PR; wait one polling cycle; verify workflow resumes in Temporal UI
  - Verify docs updated in companion repo and PR approved
