# Smoke Test Runbook

This runbook walks through a manual end-to-end verification of the GitHub Repo Documenter.

---

## Prerequisites

| Dependency | How to verify |
|---|---|
| Docker & Docker Compose | `docker compose version` |
| Ollama (local) | `ollama list` — ensure a model (e.g. `llama3`) is pulled |
| Java 17+ | `java -version` |
| A GitHub PAT with repo + PR permissions | Stored in env var `VCS_GITHUB_TOKEN` |
| A target repository to document | Configured in `application.yml` under `target-repository` |

---

## 1. Start Temporal

```bash
docker compose up -d
```

Wait for all three containers to become healthy:

```bash
docker compose ps
```

Verify the Temporal UI is reachable:

```
http://localhost:8080
```

You should see the Temporal Web UI with the `default` namespace listed.

---

## 2. Start the Application

Ensure Ollama is running locally on port 11434 with the configured model available:

```bash
ollama serve   # if not already running
ollama pull llama3
```

Export the required environment variables:

```bash
export VCS_GITHUB_TOKEN=ghp_Qq------bkPET1wKDbC
```

Update `app/src/main/resources/application.yml` with your target repository details:

```yaml
vcs:
  provider: github
  bot-username: antdoc2000

target-repository:
  owner: antonmaeso
  repo: https://github.com/antonmaeso/antdocs
  ref: main
```

Start the application:

```bash
./mvnw spring-boot:run -pl app
```

The application starts on port **8081** (Temporal UI occupies 8080).

---

## 3. Trigger a Documentation Run

```bash
curl -s -X POST http://localhost:8081/api/documentation/run | jq .
```

Expected response — HTTP 202 with a `runId`:

```json
{ "runId": "<uuid>" }
```

Poll the status until `COMPLETED`:

```bash
curl -s http://localhost:8081/api/documentation/run/<runId>/status | jq .
```

### Verify

- [ ] Status transitions from `IN_PROGRESS` → `COMPLETED`.
- [ ] A companion repository named `<repo>_ai_documentation` has been created under the same owner.
- [ ] The companion repo contains Markdown files mirroring the source repo structure.
- [ ] A top-level `README.md` exists in the companion repo summarising the project.

---

## 4. Verify PR Detection

Raise a test pull request on the target repository (e.g. add or modify a source file).

Wait at least one polling cycle (default: 60 seconds, configured via `polling.interval-seconds`).

### Verify

- [ ] A workflow with ID `pr-review-<owner>-<repo>-<prNumber>` appears in the Temporal UI under the `default` namespace.
- [ ] The workflow status is `Running`.
- [ ] The bot posts a comment on the PR (if the ReviewAgent decides clarification is needed) OR the PR is approved and docs are updated (if the ReviewAgent decides autonomously).

---

## 5. Test the Conversation Loop

If the bot posted a question on the PR:

1. Reply to the bot's comment with a meaningful answer.
2. Wait one polling cycle (60 seconds).

### Verify

- [ ] The workflow in Temporal UI shows a new event (signal received).
- [ ] The bot either posts a follow-up question (if more clarification needed) or approves the PR.

---

## 6. Verify Final State

After the workflow completes:

- [ ] The PR is approved by the bot account.
- [ ] Updated documentation files are pushed to the companion repository reflecting the PR changes.
- [ ] The workflow status in Temporal UI is `Completed`.

---

## 7. Timeout Behaviour (Optional)

To test the timeout path:

1. Raise a PR that triggers a clarification question from the bot.
2. Do **not** reply within the configured timeout (`temporal.pr-review-workflow.signal-timeout-seconds`, default 24h — reduce to a short value like 120 for testing).
3. After the timeout elapses, verify:
   - [ ] The bot posts a follow-up comment noting no response was received.
   - [ ] The workflow completes without approving the PR or updating docs.

---

## Cleanup

```bash
docker compose down -v   # stops Temporal and removes volumes
```
