package com.example.documenter.crawler;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.TreeEntry;

/**
 * Orchestrates file tree fetch and content retrieval for a repository.
 *
 * <p>Skips submodule entries (type {@code "commit"}) with a warning log.
 * Applies the configured {@link ExtensionFilter} to each blob entry.
 * On VCS API error for a single file, logs the error and continues.
 * Rate-limit handling is delegated to the {@link VCSProvider} implementation.
 */
@Service
public class RepositoryCrawler {

    private static final Logger log = LoggerFactory.getLogger(RepositoryCrawler.class);

    private final VCSProvider vcsProvider;
    private final ExtensionFilter extensionFilter;

    public RepositoryCrawler(VCSProvider vcsProvider, ExtensionFilter extensionFilter) {
        this.vcsProvider = vcsProvider;
        this.extensionFilter = extensionFilter;
    }

    /**
     * Crawls the given repository, fetching content for all files that pass the extension filter.
     *
     * @param owner the repository owner
     * @param repo  the repository name
     * @param ref   the Git ref (branch, tag, or commit SHA)
     * @return a list of successfully crawled files
     */
    public List<CrawledFile> crawl(String owner, String repo, String ref) {
        List<TreeEntry> tree = vcsProvider.fetchFileTree(owner, repo, ref);
        List<CrawledFile> results = new ArrayList<>();

        for (TreeEntry entry : tree) {
            if ("commit".equals(entry.type())) {
                log.warn("Skipping submodule: {}", entry.path());
                continue;
            }
            if (!"blob".equals(entry.type())) {
                continue;
            }
            if (!extensionFilter.accepts(entry.path())) {
                continue;
            }
            try {
                String content = vcsProvider.fetchFileContent(owner, repo, entry.path(), ref);
                results.add(new CrawledFile(entry.path(), content));
            } catch (Exception e) {
                log.error("Failed to fetch file {}: {}", entry.path(), e.getMessage());
            }
        }

        return results;
    }
}
