# Requirements Document

## Introduction

A repository documentation tool built as a modular monolith using Spring AI. The tool crawls a target repository (GitHub, GitLab, or Bitbucket), generates AI-powered documentation for the codebase, stores that documentation in a dedicated companion repository, and interacts with open pull requests through an autonomous AI-driven review loop. When a PR is raised, the system analyses the diff against existing documentation, decides whether clarification is needed, and either updates the docs autonomously or conducts a multi-turn conversation with the developer until it has enough information to do so. Ollama is used as the AI provider during testing.

## Glossary

- **Documenter**: The main application responsible for orchestrating crawling, documentation generation, and PR interaction.
- **Crawler**: The module responsible for fetching repository content (files, structure, commits) via the VCSGateway.
- **DocumentationGenerator**: The module responsible for producing human-readable documentation from source code using Spring AI.
- **PRReviewer**: The module responsible for detecting new pull requests via polling and starting a PRReviewWorkflow for each qualifying change.
- **DocumentationRepository**: The companion repository named `{original_repo_name}_ai_documentation` where generated documentation is stored.
- **AIProvider**: The configured AI backend used by Spring AI (e.g., Ollama for testing, any compatible provider for production).
- **VCSGateway**: The module that defines the provider-agnostic VCSProvider interface and exposes domain-oriented interfaces to other modules.
- **VCSProvider**: The provider-agnostic interface within the VCSGateway module that abstracts all version control system operations (fetching files, listing PRs, posting comments, approving PRs, etc.).
- **GitHubProvider**: The concrete VCSProvider implementation that communicates with the GitHub REST API.
- **GitLabProvider**: The concrete VCSProvider implementation that communicates with the GitLab REST API.
- **BitbucketProvider**: The concrete VCSProvider implementation that communicates with the Bitbucket REST API.
- **TargetRepository**: The repository being documented, hosted on the configured VCS provider.
- **Temporal**: A durable workflow orchestration platform that persists workflow state and enables long-running, fault-tolerant processes. Run locally via Docker Compose.
- **Workflow**: A Temporal workflow definition that orchestrates a sequence of Activities and can pause execution while waiting for external events (Signals).
- **Activity**: A Temporal activity — a single, retriable unit of work within a Workflow (e.g., posting a VCS comment, fetching a PR diff).
- **Signal**: A Temporal mechanism for delivering an external event to a running Workflow, used to resume a paused workflow when a developer responds to a PR comment.
- **PRReviewWorkflow**: The Temporal workflow that manages the full autonomous PR review loop: scanning relevant documentation, deciding whether clarification is needed, conducting a multi-turn conversation with the developer if required, updating documentation, approving the PR, and closing the workflow.
- **RelevanceScanner**: The AI component within the PRReviewWorkflow responsible for walking the documentation file tree, reading each file's short description, and identifying which documentation files are relevant to the PR diff.
- **ReviewAgent**: The primary AI agent within the PRReviewWorkflow responsible for analysing the PR diff against relevant documentation, deciding whether to update docs autonomously or ask the developer questions, evaluating developer replies, posting follow-up questions if needed, and ultimately producing updated documentation content.
- **PromptTemplate**: A Spring AI prompt template loaded from a `.st` (StringTemplate) file on the classpath (e.g., `src/main/resources/prompts/`). All AI prompts in the Documenter are managed as PromptTemplate files and are never hardcoded in Java code.
- **PersonalAccessToken**: A VCS provider-issued token used to authenticate API requests. Provided via `application.yml` configuration for each supported provider.
- **BotUsername**: The VCS account username associated with the configured PersonalAccessToken. Used by the PRCommentPoller to distinguish bot-posted comments from developer replies. Configured via `vcs.bot-username` in `application.yml`.
- **DocumentationRunEndpoint**: The REST API endpoint exposed by the Documenter that manually triggers a full documentation crawl and generation run for a configured repository.
- **WorkflowTracker**: An in-memory registry maintained by the PRReviewer module that maps `{owner}/{repo}/pr/{prNumber}` to the active Temporal workflow ID, enabling the PRCommentPoller to route developer-reply Signals to the correct workflow instance.

---

## Requirements

### Requirement 1: Repository Crawling

**User Story:** As a developer, I want the tool to crawl a repository, so that all source files and structure are available for documentation generation.

#### Acceptance Criteria

1. WHEN a valid repository URL and credentials are provided, THE Crawler SHALL fetch the full file tree of the TargetRepository using the VCSProvider.
2. WHEN the Crawler fetches the file tree, THE Crawler SHALL retrieve the raw content of each source file.
3. WHEN a file cannot be fetched due to a VCS API error, THE Crawler SHALL log the error with the file path and continue processing remaining files.
4. WHEN the VCS API returns a rate-limit response, THE Crawler SHALL delegate rate-limit handling to the VCSProvider, which SHALL wait for the provider-specific reset duration before retrying.
5. THE Crawler SHALL support configuring which file extensions to include or exclude during crawling.
6. WHEN a repository contains submodules, THE Crawler SHALL skip submodule entries and log a warning identifying each skipped submodule.

---

### Requirement 2: AI-Powered Documentation Generation

**User Story:** As a developer, I want the tool to generate documentation for each source file, so that the codebase is automatically documented without manual effort.

#### Acceptance Criteria

1. WHEN a source file is retrieved by the Crawler, THE DocumentationGenerator SHALL produce a documentation artifact for that file using the configured AIProvider.
2. THE DocumentationGenerator SHALL generate documentation that includes a summary of the file's purpose, descriptions of public functions and classes, and notable implementation details.
3. WHEN the AIProvider returns an error or times out, THE DocumentationGenerator SHALL retry the request up to 3 times with exponential backoff before logging the failure and skipping the file.
4. THE DocumentationGenerator SHALL produce documentation in Markdown format.
5. WHEN all files in the TargetRepository have been processed, THE DocumentationGenerator SHALL produce a top-level `README.md` summarizing the overall repository structure and purpose.
6. WHERE Ollama is configured as the AIProvider, THE DocumentationGenerator SHALL connect to the local Ollama endpoint and use the configured model name.
7. THE DocumentationGenerator SHALL load the per-file documentation generation prompt from a PromptTemplate file at `src/main/resources/prompts/generate-documentation.st` and the README generation prompt from `src/main/resources/prompts/generate-readme.st`. No prompt string SHALL be hardcoded in Java code.
8. WHEN generating a documentation artifact for a file, THE DocumentationGenerator SHALL include a short one-sentence description field in the artifact metadata. This description is used by the RelevanceScanner during PR review to determine file relevance without reading full file content.

---

### Requirement 3: Documentation Storage

**User Story:** As a developer, I want generated documentation stored in a dedicated repository, so that it is versioned, browsable, and separate from the source code.

#### Acceptance Criteria

1. WHEN documentation generation is complete, THE Documenter SHALL push all generated Markdown files to the DocumentationRepository named `{original_repo_name}_ai_documentation`.
2. WHEN the DocumentationRepository does not exist, THE Documenter SHALL create it under the same owner as the TargetRepository before pushing documentation. The PersonalAccessToken used MUST have write permissions on the owner's account or organisation.
3. THE Documenter SHALL mirror the directory structure of the TargetRepository within the DocumentationRepository, with each source file replaced by its corresponding Markdown documentation file.
4. WHEN a documentation file already exists in the DocumentationRepository, THE Documenter SHALL overwrite it with the newly generated content.
5. WHEN pushing to the DocumentationRepository fails, THE Documenter SHALL log the error including the affected file path and the HTTP status code returned by the VCSProvider.

---

### Requirement 4: Pull Request Detection

**User Story:** As a developer, I want the tool to detect new pull requests automatically, so that the PR review loop starts without manual intervention.

#### Acceptance Criteria

1. THE PRReviewer SHALL poll the VCSProvider for open pull requests on the TargetRepository at the interval configured by `polling.interval-seconds`.
2. WHEN a new open pull request is detected that does not already have an active entry in the WorkflowTracker, THE PRReviewer SHALL retrieve the diff for that pull request and start a PRReviewWorkflow instance.
3. WHEN the PRReviewer starts a PRReviewWorkflow, THE PRReviewer SHALL register the PR in the WorkflowTracker with the assigned Temporal workflow ID.
4. WHEN a pull request that is tracked in the WorkflowTracker is closed or merged, THE PRReviewer SHALL remove it from the WorkflowTracker.
5. THE PRReviewer SHALL use a deterministic workflow ID of the form `pr-review-{owner}-{repo}-{prNumber}` to ensure idempotency across application restarts.
6. IF the VCS API returns an error when fetching open PRs, THE PRReviewer SHALL log the error and skip that polling cycle without affecting the WorkflowTracker state.

---

### Requirement 5: Autonomous PR Review Workflow

**User Story:** As a developer, I want the tool to autonomously review my PR against existing documentation, update the docs if it has enough information, or ask me targeted questions if it needs clarification — so that documentation stays accurate without manual effort on my part.

#### Acceptance Criteria

1. WHEN a PRReviewWorkflow is started, THE ReviewAgent SHALL execute a ScanRelevantDocsActivity that walks the documentation file tree in the DocumentationRepository, reads the short description of each file, and produces a list of documentation files relevant to the PR diff.
2. WHEN the ScanRelevantDocsActivity completes, THE ReviewAgent SHALL execute a AnalyseDiffActivity that reads the full content of each relevant documentation file and the PR diff, then decides one of two outcomes: (a) sufficient information exists to update documentation autonomously, or (b) clarification is needed from the developer.
3. WHEN the ReviewAgent decides outcome (a) — no clarification needed — THE PRReviewWorkflow SHALL execute an UpdateDocumentationActivity that pushes updated documentation to the DocumentationRepository, then execute an ApprovePRActivity that approves the pull request via the VCSProvider, then complete the workflow.
4. WHEN the ReviewAgent decides outcome (b) — clarification needed — THE PRReviewWorkflow SHALL execute a PostCommentActivity that posts a targeted question to the pull request, then pause and wait for a developer-reply Signal.
5. WHEN a developer-reply Signal is received, THE ReviewAgent SHALL evaluate the reply in the context of all prior conversation turns and decide one of two outcomes: (a) sufficient information now exists to update documentation, or (b) further clarification is still needed.
6. WHEN the ReviewAgent decides outcome (a) after receiving a reply, THE PRReviewWorkflow SHALL execute UpdateDocumentationActivity, then ApprovePRActivity, then complete the workflow.
7. WHEN the ReviewAgent decides outcome (b) after receiving a reply, THE PRReviewWorkflow SHALL execute a PostCommentActivity posting a follow-up question, then pause and wait for the next developer-reply Signal. This cycle MAY repeat for as many turns as the ReviewAgent requires.
8. THE PRReviewWorkflow SHALL maintain a full conversation history (all questions asked and all developer replies received) and pass this history to the ReviewAgent on every evaluation turn so that context is never lost.
9. WHEN a developer-reply Signal is not received within the configurable timeout period (`temporal.pr-review-workflow.signal-timeout-seconds`), THE PRReviewWorkflow SHALL execute a TimeoutActivity that posts a follow-up comment noting no response was received, then complete the workflow without updating documentation or approving the PR.
10. THE UpdateDocumentationActivity SHALL receive as input the list of relevant documentation files identified by ScanRelevantDocsActivity and the AI-generated updated content for each file produced by the ReviewAgent, and SHALL push each updated file to the DocumentationRepository via the VCSProvider.
11. THE ApprovePRActivity SHALL approve the pull request via the VCSProvider using the configured PersonalAccessToken.
12. ALL question and analysis prompts used by the ReviewAgent and RelevanceScanner SHALL be loaded from PromptTemplate files. No prompt string SHALL be hardcoded in Java code.
13. IF a Temporal Activity fails, THE PRReviewWorkflow SHALL retry the Activity according to the configured retry policy before marking the workflow as failed.
14. THE PRReviewWorkflow SHALL be idempotent: starting a workflow with the workflow ID `pr-review-{owner}-{repo}-{prNumber}` when an instance already exists SHALL not create a duplicate workflow instance.

---

### Requirement 6: Developer Reply Detection

**User Story:** As a developer, I want my replies to the tool's PR comments to be detected automatically, so that the review conversation continues without manual steps.

#### Acceptance Criteria

1. THE PRCommentPoller SHALL poll the VCSProvider for new comments on each PR tracked in the WorkflowTracker at the interval configured by `polling.interval-seconds`.
2. WHEN a new comment is detected on a tracked PR whose author does not match the configured `vcs.bot-username`, THE PRCommentPoller SHALL treat it as a developer reply.
3. WHEN a developer reply is detected, THE PRCommentPoller SHALL send a `developerReplySignal` carrying the comment body to the PRReviewWorkflow instance identified by the WorkflowTracker entry for that PR.
4. THE PRCommentPoller SHALL track the ID of the most recently seen comment per PR to avoid sending duplicate Signals for the same comment across polling cycles.
5. WHEN sending a Signal to a workflow that has already completed or no longer exists in Temporal, THE PRCommentPoller SHALL log a warning and remove the PR from the WorkflowTracker.
6. THE PRCommentPoller SHALL read the polling interval from `polling.interval-seconds` in `application.yml`.

---

### Requirement 7: Spring AI and AIProvider Configuration

**User Story:** As a developer, I want the AI integration to be configurable, so that I can switch between AI providers without changing application code.

#### Acceptance Criteria

1. THE Documenter SHALL use Spring AI as the abstraction layer for all interactions with the AIProvider.
2. THE Documenter SHALL read AIProvider configuration (endpoint, model name, API key) from the application's external configuration (e.g., `application.yml`).
3. WHERE Ollama is configured as the AIProvider, THE Documenter SHALL connect to the Ollama HTTP endpoint without requiring an API key.
4. WHEN the AIProvider configuration is missing or invalid at startup, THE Documenter SHALL fail to start and log a descriptive error message identifying the missing or invalid property.

---

### Requirement 8: VCS Provider Abstraction

**User Story:** As a developer, I want the VCS integration to be abstracted behind a provider interface, so that the tool can work with GitHub, GitLab, and Bitbucket without changes to the core application logic.

#### Acceptance Criteria

1. THE VCSGateway module SHALL define a VCSProvider interface that exposes provider-agnostic operations including fetching file trees, retrieving file content, listing open pull requests, fetching PR diffs, posting PR comments, listing PR comments, approving pull requests, creating repositories, and pushing files.
2. THE VCSGateway module SHALL provide a GitHubProvider implementation of the VCSProvider interface that communicates with the GitHub REST API.
3. THE VCSGateway module SHALL provide a GitLabProvider implementation of the VCSProvider interface that communicates with the GitLab REST API.
4. THE VCSGateway module SHALL provide a BitbucketProvider implementation of the VCSProvider interface that communicates with the Bitbucket REST API.
5. THE Documenter SHALL read the active VCS provider selection from the application's external configuration using `vcs.provider`.
6. WHEN the `vcs.provider` configuration property is set to `github`, THE Documenter SHALL instantiate and use the GitHubProvider.
7. WHEN the `vcs.provider` configuration property is set to `gitlab`, THE Documenter SHALL instantiate and use the GitLabProvider.
8. WHEN the `vcs.provider` configuration property is set to `bitbucket`, THE Documenter SHALL instantiate and use the BitbucketProvider.
9. WHEN the `vcs.provider` configuration property is missing or set to an unrecognised value at startup, THE Documenter SHALL fail to start and log a descriptive error message identifying the invalid provider value.
10. THE `crawler`, `documentation`, `pr-reviewer`, and `temporal-workflows` modules SHALL depend only on the VCSProvider interface and SHALL NOT depend on any concrete provider implementation class.
11. THE GitHubProvider, GitLabProvider, and BitbucketProvider SHALL each authenticate all VCS API requests using a PersonalAccessToken read from the application's external configuration (`vcs.github.token`, `vcs.gitlab.token`, `vcs.bitbucket.token`). No OAuth application flow is supported.
12. EACH concrete VCSProvider implementation SHALL handle provider-specific rate-limit responses internally: on receiving a rate-limit error, the implementation SHALL wait for the provider-specific reset duration and retry the request, without exposing rate-limit logic to callers.

---

### Requirement 9: Modular Monolith Structure

**User Story:** As a developer, I want the codebase organized as a modular monolith, so that each concern is isolated and independently testable while remaining a single deployable unit.

#### Acceptance Criteria

1. THE Documenter SHALL be structured as a single deployable application composed of distinct modules: `crawler`, `documentation`, `pr-reviewer`, `ai-gateway`, `vcs-gateway`, and `temporal-workflows`.
2. THE `crawler` module SHALL depend only on the `vcs-gateway` module for VCS API access.
3. THE `documentation` module SHALL depend on the `ai-gateway` module for AI interactions and on the `vcs-gateway` module for pushing documentation files.
4. THE `pr-reviewer` module SHALL depend only on the `vcs-gateway` module, the `ai-gateway` module, and the `temporal-workflows` module.
5. THE `temporal-workflows` module SHALL encapsulate all Temporal workflow and Activity definitions and expose workflow client interfaces to other modules.
6. THE `vcs-gateway` module SHALL encapsulate all VCS provider client logic, define the VCSProvider interface, and expose domain-oriented interfaces to other modules.
7. THE `ai-gateway` module SHALL encapsulate all Spring AI client logic and expose domain-oriented interfaces to other modules.

---

### Requirement 10: Unit Testing

**User Story:** As a developer, I want the codebase to be fully unit tested, so that regressions are caught early and each module's behavior is verified in isolation.

#### Acceptance Criteria

1. THE Documenter SHALL include unit tests for all public methods in every module.
2. WHEN unit tests are run, THE Documenter SHALL mock all external dependencies (VCS API, AIProvider, Temporal server) so that no real network calls are made.
3. THE Documenter SHALL include tests that verify the Crawler correctly handles rate-limit responses (delegated to VCSProvider), file fetch errors, and submodule entries.
4. THE Documenter SHALL include tests that verify the DocumentationGenerator retries on AIProvider failure and produces valid Markdown output.
5. THE Documenter SHALL include tests that verify the PRReviewer does not start a duplicate workflow for a PR already present in the WorkflowTracker.
6. THE Documenter SHALL include a round-trip test verifying that documentation written to and read back from the DocumentationRepository is byte-for-byte equivalent to the generated content.
7. THE Documenter SHALL include tests that verify each concrete VCSProvider implementation (GitHubProvider, GitLabProvider, BitbucketProvider) correctly maps provider-specific API responses to the VCSProvider interface contract.
8. THE Documenter SHALL include tests that verify the correct VCSProvider implementation is instantiated for each valid value of the `vcs.provider` configuration property.
9. THE Documenter SHALL include workflow tests using the Temporal test environment that verify: the PRReviewWorkflow runs ScanRelevantDocsActivity and AnalyseDiffActivity on start; when the ReviewAgent decides no clarification is needed, UpdateDocumentationActivity and ApprovePRActivity are called and the workflow completes; when the ReviewAgent decides clarification is needed, a question is posted and the workflow pauses.
10. THE Documenter SHALL include workflow tests that verify: when a developerReplySignal is received and the ReviewAgent is satisfied, UpdateDocumentationActivity and ApprovePRActivity are called; when the ReviewAgent requires further clarification, another question is posted and the workflow pauses again.
11. THE Documenter SHALL include workflow tests that verify the PRReviewWorkflow executes the TimeoutActivity and completes without updating docs or approving the PR when a developerReplySignal is not received within the configured timeout.
12. THE Documenter SHALL include Activity unit tests that verify ScanRelevantDocsActivity, AnalyseDiffActivity, PostCommentActivity, UpdateDocumentationActivity, ApprovePRActivity, and TimeoutActivity each behave correctly in isolation with mocked VCSProvider and AIGateway dependencies.
13. THE Documenter SHALL include tests that verify each PromptTemplate file is loadable from the classpath and that the rendered prompt contains the expected variable substitutions for representative inputs.
14. THE Documenter SHALL include tests that verify the PRCommentPoller correctly identifies developer replies (non-bot comments), sends the developerReplySignal to the correct workflow instance, and does not send duplicate signals for the same comment.
15. THE Documenter SHALL include tests that verify the DocumentationRunEndpoint triggers a full documentation run and returns the expected HTTP response for both a valid repository configuration and a missing configuration.
16. THE Documenter SHALL include tests that verify the WorkflowTracker correctly registers new PRs, returns the workflow ID for a tracked PR, and removes entries when a PR is closed.

---

### Requirement 11: Manual Documentation Run API

**User Story:** As a developer, I want to trigger a full documentation run via a REST API endpoint, so that I can kick off the initial documentation generation on demand without restarting the application.

#### Acceptance Criteria

1. THE Documenter SHALL expose a REST API endpoint (`POST /api/documentation/run`) that triggers a full documentation crawl and generation run for the configured TargetRepository and returns HTTP 202 Accepted immediately with a run ID.
2. WHEN the DocumentationRunEndpoint is called, THE Documenter SHALL execute the full pipeline asynchronously: crawl the TargetRepository, generate documentation for all source files, and push the results to the DocumentationRepository.
3. THE Documenter SHALL expose a `GET /api/documentation/run/{runId}/status` endpoint that returns the current status (`IN_PROGRESS`, `COMPLETED`, or `FAILED`) and a summary message for the given run ID.
4. WHEN the DocumentationRunEndpoint is called and a run is already in progress, THE Documenter SHALL return an HTTP 409 Conflict response and SHALL NOT start a second concurrent run.
5. WHEN the DocumentationRunEndpoint is called and the TargetRepository configuration is missing or invalid, THE Documenter SHALL return an HTTP 400 Bad Request response with a descriptive error message.
6. WHEN a full documentation run fails, the status endpoint SHALL return `FAILED` with a summary of the failure.
7. AFTER the initial documentation run, THE PRReviewWorkflow SHALL handle all subsequent documentation updates automatically via the polling-based PR review loop without requiring the DocumentationRunEndpoint to be called again.
