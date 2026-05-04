package com.example.documenter.crawler;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.documenter.crawler.config.CrawlerProperties;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.VcsApiException;
import com.example.documenter.vcsgateway.domain.TreeEntry;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for the Crawler module.
 *
 * <p>Uses jqwik to verify universal invariants across randomly generated inputs.
 */
class CrawlerPropertyTest {

    private static final String[] EXTENSIONS = {".java", ".kt", ".py", ".ts", ".js", ".go", ".md", ".txt", ".xml", ".yml"};

    @Provide
    Arbitrary<String> filePaths() {
        Arbitrary<String> dirs = Arbitraries.of("src", "lib", "test", "docs", "config", "src/main", "src/test");
        Arbitrary<String> names = Arbitraries.of("Main", "App", "Utils", "Config", "README", "data", "index", "helper");
        Arbitrary<String> exts = Arbitraries.of(EXTENSIONS);
        return Combinators.combine(dirs, names, exts).as((dir, name, ext) -> dir + "/" + name + ext);
    }

    @Provide
    Arbitrary<List<TreeEntry>> treesWithBlobs() {
        Arbitrary<TreeEntry> blobEntry = filePaths().map(path -> new TreeEntry(path, "blob", "sha-" + path.hashCode()));
        return blobEntry.list().ofMinSize(1).ofMaxSize(20);
    }

    @Provide
    Arbitrary<List<TreeEntry>> treesWithMixedTypes() {
        Arbitrary<TreeEntry> blobEntry = filePaths().map(path -> new TreeEntry(path, "blob", "sha-blob"));
        Arbitrary<TreeEntry> treeEntry = Arbitraries.of("src", "lib", "docs").map(path -> new TreeEntry(path, "tree", "sha-tree"));
        Arbitrary<TreeEntry> commitEntry = Arbitraries.of("libs/external", "vendor/dep", "submodules/core")
                .map(path -> new TreeEntry(path, "commit", "sha-commit"));

        return Arbitraries.oneOf(blobEntry, treeEntry, commitEntry).list().ofMinSize(1).ofMaxSize(20);
    }

    // ---- Property 1: crawlerFetchesContentForEveryBlob ----

    /**
     * For any tree with N blobs passing the filter, crawler returns exactly N results.
     *
     * <p>Validates: Requirements 1.1, 1.2
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 1: crawlerFetchesContentForEveryBlob
    void crawlerFetchesContentForEveryBlob(@ForAll("treesWithBlobs") List<TreeEntry> tree) {
        // Deduplicate paths to avoid ambiguous mock setups
        List<TreeEntry> uniqueTree = tree.stream()
                .collect(Collectors.toMap(TreeEntry::path, e -> e, (a, b) -> a))
                .values().stream().toList();

        VCSProvider vcsProvider = mock(VCSProvider.class);
        when(vcsProvider.fetchFileTree("owner", "repo", "main")).thenReturn(uniqueTree);

        // Accept all files
        ExtensionFilter filter = createAcceptAllFilter();

        for (TreeEntry entry : uniqueTree) {
            when(vcsProvider.fetchFileContent("owner", "repo", entry.path(), "main"))
                    .thenReturn("content of " + entry.path());
        }

        RepositoryCrawler crawler = new RepositoryCrawler(vcsProvider, filter);
        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        long blobCount = uniqueTree.stream().filter(e -> "blob".equals(e.type())).count();
        assertEquals(blobCount, result.size());

        Set<String> expectedPaths = uniqueTree.stream()
                .filter(e -> "blob".equals(e.type()))
                .map(TreeEntry::path)
                .collect(Collectors.toSet());
        Set<String> actualPaths = result.stream().map(CrawledFile::path).collect(Collectors.toSet());
        assertEquals(expectedPaths, actualPaths);
    }

    // ---- Property 2: partialFailuresDoNotAbortCrawl ----

    /**
     * For any tree where K random files fail, crawler returns N-K results.
     *
     * <p>Validates: Requirement 1.3
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 2: partialFailuresDoNotAbortCrawl
    void partialFailuresDoNotAbortCrawl(@ForAll("treesWithBlobs") List<TreeEntry> tree) {
        // Deduplicate paths
        List<TreeEntry> uniqueTree = tree.stream()
                .collect(Collectors.toMap(TreeEntry::path, e -> e, (a, b) -> a))
                .values().stream().toList();

        VCSProvider vcsProvider = mock(VCSProvider.class);
        when(vcsProvider.fetchFileTree("owner", "repo", "main")).thenReturn(uniqueTree);

        ExtensionFilter filter = createAcceptAllFilter();

        // Make roughly half the files fail
        List<TreeEntry> blobs = uniqueTree.stream().filter(e -> "blob".equals(e.type())).toList();
        int failCount = 0;
        for (int i = 0; i < blobs.size(); i++) {
            TreeEntry entry = blobs.get(i);
            if (i % 2 == 0) {
                when(vcsProvider.fetchFileContent("owner", "repo", entry.path(), "main"))
                        .thenThrow(new VcsApiException("Simulated failure", 500));
                failCount++;
            } else {
                when(vcsProvider.fetchFileContent("owner", "repo", entry.path(), "main"))
                        .thenReturn("content of " + entry.path());
            }
        }

        RepositoryCrawler crawler = new RepositoryCrawler(vcsProvider, filter);
        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        assertEquals(blobs.size() - failCount, result.size());
    }

    // ---- Property 3: extensionFilterIsRespected ----

    /**
     * Every returned file path matches the include list and doesn't match the exclude list.
     *
     * <p>Validates: Requirement 1.5
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 3: extensionFilterIsRespected
    void extensionFilterIsRespected(@ForAll("treesWithBlobs") List<TreeEntry> tree) {
        // Deduplicate paths
        List<TreeEntry> uniqueTree = tree.stream()
                .collect(Collectors.toMap(TreeEntry::path, e -> e, (a, b) -> a))
                .values().stream().toList();

        List<String> includeExts = List.of(".java", ".kt");
        List<String> excludeExts = List.of(".md", ".txt");

        CrawlerProperties props = new CrawlerProperties();
        props.setIncludeExtensions(includeExts);
        props.setExcludeExtensions(excludeExts);
        ExtensionFilter filter = new ExtensionFilter(props);

        VCSProvider vcsProvider = mock(VCSProvider.class);
        when(vcsProvider.fetchFileTree("owner", "repo", "main")).thenReturn(uniqueTree);

        for (TreeEntry entry : uniqueTree) {
            if ("blob".equals(entry.type()) && filter.accepts(entry.path())) {
                when(vcsProvider.fetchFileContent("owner", "repo", entry.path(), "main"))
                        .thenReturn("content");
            }
        }

        RepositoryCrawler crawler = new RepositoryCrawler(vcsProvider, filter);
        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        for (CrawledFile file : result) {
            // Must match at least one include extension
            boolean matchesInclude = includeExts.stream().anyMatch(ext -> file.path().endsWith(ext));
            assertTrue(matchesInclude, "File " + file.path() + " does not match any include extension");

            // Must not match any exclude extension
            boolean matchesExclude = excludeExts.stream().anyMatch(ext -> file.path().endsWith(ext));
            assertFalse(matchesExclude, "File " + file.path() + " matches an exclude extension");
        }
    }

    // ---- Property 4: submoduleEntriesAreNeverIncluded ----

    /**
     * No returned file has type "commit" (submodule).
     *
     * <p>Validates: Requirement 1.6
     */
    @Property(tries = 25)
    // Feature: github-repo-documenter, Property 4: submoduleEntriesAreNeverIncluded
    void submoduleEntriesAreNeverIncluded(@ForAll("treesWithMixedTypes") List<TreeEntry> tree) {
        // Deduplicate paths
        List<TreeEntry> uniqueTree = tree.stream()
                .collect(Collectors.toMap(TreeEntry::path, e -> e, (a, b) -> a))
                .values().stream().toList();

        VCSProvider vcsProvider = mock(VCSProvider.class);
        when(vcsProvider.fetchFileTree("owner", "repo", "main")).thenReturn(uniqueTree);

        ExtensionFilter filter = createAcceptAllFilter();

        // Set up content fetch for blobs only
        for (TreeEntry entry : uniqueTree) {
            if ("blob".equals(entry.type())) {
                when(vcsProvider.fetchFileContent("owner", "repo", entry.path(), "main"))
                        .thenReturn("content of " + entry.path());
            }
        }

        RepositoryCrawler crawler = new RepositoryCrawler(vcsProvider, filter);
        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        // Collect all submodule paths
        Set<String> submodulePaths = uniqueTree.stream()
                .filter(e -> "commit".equals(e.type()))
                .map(TreeEntry::path)
                .collect(Collectors.toSet());

        // No result should have a submodule path
        for (CrawledFile file : result) {
            assertFalse(submodulePaths.contains(file.path()),
                    "Submodule entry " + file.path() + " should not be in crawl results");
        }
    }

    // ---- Helper methods ----

    private ExtensionFilter createAcceptAllFilter() {
        CrawlerProperties props = new CrawlerProperties();
        props.setIncludeExtensions(List.of());
        props.setExcludeExtensions(List.of());
        return new ExtensionFilter(props);
    }
}
