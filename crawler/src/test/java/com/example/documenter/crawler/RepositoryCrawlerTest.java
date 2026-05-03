package com.example.documenter.crawler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.documenter.crawler.config.CrawlerProperties;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.VcsApiException;
import com.example.documenter.vcsgateway.domain.TreeEntry;

/**
 * Unit tests for {@link RepositoryCrawler}.
 */
@ExtendWith(MockitoExtension.class)
class RepositoryCrawlerTest {

    @Mock
    private VCSProvider vcsProvider;

    private RepositoryCrawler crawler;

    /** Creates an ExtensionFilter that accepts all files. */
    private ExtensionFilter acceptAllFilter() {
        CrawlerProperties props = new CrawlerProperties();
        props.setIncludeExtensions(List.of());
        props.setExcludeExtensions(List.of());
        return new ExtensionFilter(props);
    }

    /** Creates an ExtensionFilter with the given include/exclude lists. */
    private ExtensionFilter filterWith(List<String> include, List<String> exclude) {
        CrawlerProperties props = new CrawlerProperties();
        props.setIncludeExtensions(include);
        props.setExcludeExtensions(exclude);
        return new ExtensionFilter(props);
    }

    @BeforeEach
    void setUp() {
        crawler = new RepositoryCrawler(vcsProvider, acceptAllFilter());
    }

    @Test
    void emptyTree_returnsEmptyResult() {
        when(vcsProvider.fetchFileTree("owner", "repo", "main")).thenReturn(List.of());

        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        assertTrue(result.isEmpty());
    }

    @Test
    void submoduleEntries_areSkipped() {
        TreeEntry submodule = new TreeEntry("libs/external", "commit", "abc123");
        TreeEntry blob = new TreeEntry("src/Main.java", "blob", "def456");

        when(vcsProvider.fetchFileTree("owner", "repo", "main"))
                .thenReturn(List.of(submodule, blob));
        when(vcsProvider.fetchFileContent("owner", "repo", "src/Main.java", "main"))
                .thenReturn("content");

        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        assertEquals(1, result.size());
        assertEquals("src/Main.java", result.get(0).path());
        // Verify fetchFileContent was never called for the submodule
        verify(vcsProvider, never()).fetchFileContent(eq("owner"), eq("repo"), eq("libs/external"), anyString());
    }

    @Test
    void extensionFilterRejectsFile_fileNotFetched() {
        TreeEntry blob = new TreeEntry("README.md", "blob", "abc123");

        when(vcsProvider.fetchFileTree("owner", "repo", "main")).thenReturn(List.of(blob));

        // Use a filter that only includes .java files
        crawler = new RepositoryCrawler(vcsProvider, filterWith(List.of(".java"), List.of()));

        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        assertTrue(result.isEmpty());
        verify(vcsProvider, never()).fetchFileContent(anyString(), anyString(), eq("README.md"), anyString());
    }

    @Test
    void fetchFileContentThrows_errorLoggedAndOtherFilesStillProcessed() {
        TreeEntry blob1 = new TreeEntry("src/A.java", "blob", "aaa");
        TreeEntry blob2 = new TreeEntry("src/B.java", "blob", "bbb");

        when(vcsProvider.fetchFileTree("owner", "repo", "main"))
                .thenReturn(List.of(blob1, blob2));
        when(vcsProvider.fetchFileContent("owner", "repo", "src/A.java", "main"))
                .thenThrow(new VcsApiException("Not found", 404));
        when(vcsProvider.fetchFileContent("owner", "repo", "src/B.java", "main"))
                .thenReturn("content B");

        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        assertEquals(1, result.size());
        assertEquals("src/B.java", result.get(0).path());
        assertEquals("content B", result.get(0).content());
    }

    @Test
    void allBlobsFetchedWhenFilterAcceptsAll() {
        TreeEntry blob1 = new TreeEntry("src/A.java", "blob", "aaa");
        TreeEntry blob2 = new TreeEntry("src/B.java", "blob", "bbb");
        TreeEntry blob3 = new TreeEntry("src/C.java", "blob", "ccc");

        when(vcsProvider.fetchFileTree("owner", "repo", "main"))
                .thenReturn(List.of(blob1, blob2, blob3));
        when(vcsProvider.fetchFileContent("owner", "repo", "src/A.java", "main")).thenReturn("A");
        when(vcsProvider.fetchFileContent("owner", "repo", "src/B.java", "main")).thenReturn("B");
        when(vcsProvider.fetchFileContent("owner", "repo", "src/C.java", "main")).thenReturn("C");

        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        assertEquals(3, result.size());
        assertEquals("src/A.java", result.get(0).path());
        assertEquals("src/B.java", result.get(1).path());
        assertEquals("src/C.java", result.get(2).path());
    }

    @Test
    void treeEntries_areSkipped() {
        TreeEntry tree = new TreeEntry("src", "tree", "abc123");
        TreeEntry blob = new TreeEntry("src/Main.java", "blob", "def456");

        when(vcsProvider.fetchFileTree("owner", "repo", "main"))
                .thenReturn(List.of(tree, blob));
        when(vcsProvider.fetchFileContent("owner", "repo", "src/Main.java", "main"))
                .thenReturn("content");

        List<CrawledFile> result = crawler.crawl("owner", "repo", "main");

        assertEquals(1, result.size());
        assertEquals("src/Main.java", result.get(0).path());
    }
}
