package com.example.documenter.documentation;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.documenter.documentation.domain.DocumentationArtifact;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.VcsApiException;

/**
 * Pushes generated documentation artifacts to the companion repository.
 *
 * <p>The companion repo is named {@code {original_repo_name}_ai_documentation}.
 * If the repo does not exist, it is created. Each artifact is pushed as
 * {@code {sourcePath}.md}. Push failures are logged and skipped.
 */
@Service
public class DocumentationStorage {

    private static final Logger log = LoggerFactory.getLogger(DocumentationStorage.class);

    private final VCSProvider vcsProvider;

    public DocumentationStorage(VCSProvider vcsProvider) {
        this.vcsProvider = vcsProvider;
    }

    /**
     * Stores all documentation artifacts in the companion repository.
     *
     * @param artifacts the documentation artifacts to store
     * @param owner     the repository owner
     * @param repoName  the original repository name
     */
    public void store(List<DocumentationArtifact> artifacts, String owner, String repoName) {
        String companionRepo = repoName + "_ai_documentation";

        ensureCompanionRepoExists(owner, companionRepo);

        for (DocumentationArtifact artifact : artifacts) {
            String targetPath = deriveTargetPath(artifact.sourcePath());
            String commitMessage = "docs: update " + targetPath;

            try {
                vcsProvider.pushFile(owner, companionRepo, targetPath, artifact.markdownContent(), commitMessage);
            } catch (VcsApiException e) {
                log.error("Failed to push documentation for path {}: HTTP {} - {}",
                        targetPath, e.getHttpStatus(), e.getMessage());
            }
        }
    }

    private void ensureCompanionRepoExists(String owner, String companionRepo) {
        try {
            vcsProvider.fetchFileTree(owner, companionRepo, "main");
        } catch (VcsApiException e) {
            if (e.getHttpStatus() == 404) {
                log.info("Companion repo {}/{} not found, creating it", owner, companionRepo);
                vcsProvider.createRepository(owner, companionRepo, false);
            } else if (e.getHttpStatus() == 409) {
                // Repository exists but is empty (no commits/branches yet)
                log.info("Companion repo {}/{} exists but is empty, will initialize on first push", owner, companionRepo);
            } else {
                throw e;
            }
        }
    }

    /**
     * Derives the target path for a documentation artifact.
     * For source files, appends {@code .md}. For README.md, keeps as-is.
     */
    static String deriveTargetPath(String sourcePath) {
        if ("README.md".equals(sourcePath)) {
            return sourcePath;
        }
        return sourcePath + ".md";
    }
}
