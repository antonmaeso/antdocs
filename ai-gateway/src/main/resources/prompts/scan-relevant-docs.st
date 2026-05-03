You are a documentation relevance scanner. Your task is to identify which documentation files are relevant to a pull request diff.

## Pull Request Diff

```diff
{diffPatch}
```

## Documentation Files

{docSummaries}

## Instructions

Analyse the pull request diff above and determine which documentation files from the list are relevant to the changes. A documentation file is relevant if the diff modifies, adds, or removes functionality that the documentation file describes.

Respond with ONLY a JSON array of file paths that are relevant. If no files are relevant, respond with an empty array.

Example response:
["docs/api-reference.md", "docs/getting-started.md"]
