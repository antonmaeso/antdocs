# Systems Design Review: GitHub Repo Documenter

**Reviewer:** Systems Design Expert  
**Date:** May 3, 2026  
**Documents Reviewed:** `requirements.md`, `design.md`  
**Status:** All issues resolved — approved for implementation.

---

## Resolution Log

### Critical Issues (all resolved)

**1. Signal delivery mechanism underspecified → RESOLVED**

The PR review loop has been redesigned from the ground up. A `WorkflowTracker` component (in-memory `ConcurrentHashMap`) now maps `{owner}/{repo}/pr/{prNumber}` to the active Temporal workflow ID. The `PRReviewer` registers entries on workflow start and removes them on PR close. The `PRCommentPoller` uses the tracker to route `developerReplySignal` events to the correct workflow instance. Bot identity is configured via `vcs.bot-username` in `application.yml`. Signal delivery to a completed or missing workflow logs a warning and removes the PR from the tracker.

**2. `UpdateDocumentationActivity` output contract undefined → RESOLVED**

`AnalyseDiffActivity` now returns a `ReviewDecision` sealed type. The `Autonomous` variant carries a `List<DocUpdate>` (path + new content) that is passed directly to `UpdateDocumentationActivity`, which calls `VCSProvider.pushFile()` for each entry. File targeting is explicit: the ReviewAgent produces updated content for the specific doc files identified by `ScanRelevantDocsActivity`.

**3. Qualifying change detection undefined → RESOLVED**

The concept of "qualifying change detection" has been removed. The new design starts one `PRReviewWorkflow` per PR (not per diff hunk), triggered by the `PRReviewer` polling for new open PRs. The `ScanRelevantDocsActivity` and `AnalyseDiffActivity` handle relevance and significance assessment using the AI, which is the appropriate tool for this judgment. Property 11 has been rewritten accordingly.

---

### Major Issues (all resolved)

**4. Req 8.3 contradicted design dependency table → RESOLVED**

Requirement 9.3 (renumbered) now correctly states: "THE `documentation` module SHALL depend on the `ai-gateway` module for AI interactions and on the `vcs-gateway` module for pushing documentation files." The design dependency table matches.

**5. Missing `generate-readme.st` prompt template → RESOLVED**

`generate-readme.st` is now listed in both the prompt template table in the design and in Requirement 2.7. `AIGateway.generateRepositoryReadme()` loads this template.

**6. Rate-limit handling was GitHub-specific → RESOLVED**

Rate-limit handling has been moved into each concrete `VCSProvider` implementation. Each provider handles its own rate-limit header format internally. The `Crawler` no longer contains any rate-limit logic. Requirement 1.4 and Requirement 8.12 reflect this. The `VCSProvider` interface is transparent to callers.

**7. Synchronous documentation run would time out → RESOLVED**

The `DocumentationRunEndpoint` now returns HTTP 202 Accepted immediately with a `runId`. The pipeline runs asynchronously. A `GET /api/documentation/run/{runId}/status` endpoint exposes run state. The `AtomicBoolean` has been replaced with `AtomicReference<DocumentationRun>` using the existing `DocumentationRun` record.

---

### Minor Issues (all resolved)

**8. `DocumentationRun` record was defined but unused → RESOLVED**

`DocumentationRun` is now used as the state tracked by `AtomicReference<DocumentationRun>` in the run concurrency guard, and as the response body for the status endpoint. The `runId` field has been added to the record.

**9. PAT write permission requirement not stated → RESOLVED**

Requirement 3.2 now explicitly states: "The PersonalAccessToken used MUST have write permissions on the owner's account or organisation." Startup validation checks for PAT presence; permission scope validation is noted as provider-dependent.

**10. Workflow signal timeout config key not defined → RESOLVED**

`temporal.pr-review-workflow.signal-timeout-seconds` is now defined in the `application.yml` structure in the design, with a default of 86400 seconds (24 hours). Requirement 5.9 references this key.

**11. `PRReviewState` missing `diffPatch` field → RESOLVED**

`PRReviewState` now includes `diffPatch`, `relevantDocPaths`, and `history` fields. The full conversation history (`List<ConversationTurn>`) is carried in workflow state and passed to every `AnalyseDiffActivity` invocation.

---

## Remaining Open Questions

These are not blockers for implementation but should be decided before the first production deployment:

1. **Single-instance assumption** — the `WorkflowTracker` and `AtomicReference` concurrency guard are in-memory and only correct for a single application instance. If horizontal scaling is ever needed, the tracker needs a distributed store and the concurrency guard needs a distributed lock.

2. **PR comment threading** — the design posts each question as an independent top-level PR comment. If the team prefers threaded replies under a single root comment, the `VCSProvider.postPullRequestComment` interface will need a `replyToCommentId` parameter.

3. **GitLab and Bitbucket priority** — all three providers are required by the spec. If timeline is tight, GitLab and Bitbucket implementations could be deferred to a second milestone with GitHub as the initial target.
