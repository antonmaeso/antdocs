# antdocs

An AI-powered repository documentation tool built as a Spring Boot modular monolith. It crawls a target repository, generates Markdown documentation for every source file using a local LLM, stores the output in a companion repo, and autonomously reviews pull requests — updating docs or asking the developer clarifying questions via a durable Temporal workflow.

## How It Works

1. You trigger a documentation run via REST API (`POST /api/documentation/run`)
2. The crawler fetches the file tree from your repo (GitHub, GitLab, or Bitbucket)
3. Spring AI + Ollama generates Markdown docs for each source file
4. Docs are pushed to a companion repo named `{your-repo}_ai_documentation`
5. A poller watches for new PRs — when one appears, a Temporal workflow kicks off that:
   - Scans existing docs for relevance to the diff
   - Either updates docs autonomously or asks the developer targeted questions
   - Approves the PR once documentation is up to date

## Prerequisites

- Java 21
- Maven 3.9+
- Docker & Docker Compose
- [Ollama](https://ollama.com/) installed and running locally

## Setup: Self-Documenting This Repository

Follow these steps to point antdocs at itself so it generates documentation for its own codebase.

### 1. Pull the LLM model

```bash
ollama pull llama3
```

Verify it's running:

```bash
curl http://localhost:11434/api/tags
```

### 2. Start Temporal (via Docker Compose)

```bash
docker compose up -d
```

This starts PostgreSQL, the Temporal server, and the Temporal UI (available at http://localhost:8080).

### 3. Create a VCS personal access token

You need a token with repo read/write permissions so antdocs can read this repository and push documentation to the companion repo.

For GitHub, create a [fine-grained personal access token](https://github.com/settings/tokens?type=beta) with:
- **Repository access**: select this repo (and the companion repo once it exists, or use "All repositories")
- **Permissions**: Contents (read/write), Pull requests (read/write)

Export it:

```bash
export VCS_GITHUB_TOKEN=ghp_your_token_here
```

### 4. Configure `application.yml`

Edit `app/src/main/resources/application.yml` to point at this repository:

```yaml
vcs:
  provider: github
  bot-username: your-github-username   # the account that owns the PAT
  github:
    token: ${VCS_GITHUB_TOKEN}
    api-base-url: https://api.github.com

target-repository:
  owner: your-github-username-or-org   # owner of this repo
  repo: antdocs                        # this repo's name
  ref: main

crawler:
  include-extensions: [.java, .kt, .py, .ts, .js, .go, .yml, .xml]
  exclude-extensions: [.md, .txt]

server:
  port: 8081                           # avoid conflict with Temporal UI on 8080

spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: llama3

temporal:
  host: localhost
  port: 7233
  namespace: default
  pr-review-workflow:
    signal-timeout-seconds: 86400

polling:
  interval-seconds: 60
```

### 5. Build the project

```bash
./mvnw clean install -DskipTests
```

### 6. Run the application

```bash
./mvnw spring-boot:run -pl app
```

### 7. Trigger the initial documentation run

```bash
curl -X POST http://localhost:8081/api/documentation/run
```

This returns a `runId`. Check progress with:

```bash
curl http://localhost:8081/api/documentation/run/{runId}/status
```

Once complete, a companion repository named `antdocs_ai_documentation` will appear under your GitHub account containing generated Markdown docs mirroring this repo's structure.

### 8. Ongoing PR reviews

After the initial run, the PR poller automatically picks up new pull requests every 60 seconds (configurable via `polling.interval-seconds`). When a PR is opened:

- The Temporal workflow scans existing docs for relevance
- If the AI can update docs from the diff alone, it pushes updates and approves the PR
- If clarification is needed, it posts a comment on the PR and waits for your reply
- The conversation continues until the AI is satisfied, then docs are updated and the PR is approved

You can monitor active workflows in the Temporal UI at http://localhost:8080.

Note: the Spring Boot app runs on port 8081 to avoid conflicting with the Temporal UI on 8080.

## Project Structure

```
antdocs/
├── app/                    # Spring Boot application entry point & config
├── ai-gateway/             # Spring AI integration (Ollama)
├── vcs-gateway/            # VCS provider abstraction (GitHub, GitLab, Bitbucket)
├── crawler/                # Repository file tree crawling
├── documentation/          # Doc generation, storage, and REST endpoint
├── pr-reviewer/            # PR detection, comment polling, workflow tracker
├── temporal-workflows/     # Temporal workflow & activity definitions
└── docker-compose.yml      # Temporal + PostgreSQL infrastructure
```

## Running Tests

```bash
./mvnw test
```

All external dependencies (VCS API, AI provider, Temporal server) are mocked in tests — no network calls required.

## Supported VCS Providers

Set `vcs.provider` in `application.yml` to one of:

| Provider | Config prefix | Token env var |
|---|---|---|
| GitHub | `vcs.github.*` | `VCS_GITHUB_TOKEN` |
| GitLab | `vcs.gitlab.*` | `VCS_GITLAB_TOKEN` |
| Bitbucket | `vcs.bitbucket.*` | `VCS_BITBUCKET_TOKEN` |



