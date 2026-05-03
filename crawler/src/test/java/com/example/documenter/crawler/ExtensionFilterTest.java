package com.example.documenter.crawler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.example.documenter.crawler.config.CrawlerProperties;

/**
 * Unit tests for {@link ExtensionFilter}.
 */
class ExtensionFilterTest {

    private ExtensionFilter createFilter(List<String> include, List<String> exclude) {
        CrawlerProperties props = new CrawlerProperties();
        props.setIncludeExtensions(include);
        props.setExcludeExtensions(exclude);
        return new ExtensionFilter(props);
    }

    // ---- Include-only filter ----

    @Test
    void includeOnlyFilter_acceptsMatchingExtension() {
        ExtensionFilter filter = createFilter(List.of(".java"), List.of());
        assertTrue(filter.accepts("src/Main.java"));
    }

    @Test
    void includeOnlyFilter_rejectsNonMatchingExtension() {
        ExtensionFilter filter = createFilter(List.of(".java"), List.of());
        assertFalse(filter.accepts("README.md"));
    }

    @Test
    void includeOnlyFilter_acceptsAnyMatchingExtension() {
        ExtensionFilter filter = createFilter(List.of(".java", ".kt"), List.of());
        assertTrue(filter.accepts("src/Main.java"));
        assertTrue(filter.accepts("src/Main.kt"));
        assertFalse(filter.accepts("src/main.py"));
    }

    // ---- Exclude-only filter ----

    @Test
    void excludeOnlyFilter_rejectsMatchingExtension() {
        ExtensionFilter filter = createFilter(List.of(), List.of(".md"));
        assertFalse(filter.accepts("README.md"));
    }

    @Test
    void excludeOnlyFilter_acceptsNonMatchingExtension() {
        ExtensionFilter filter = createFilter(List.of(), List.of(".md"));
        assertTrue(filter.accepts("src/Main.java"));
    }

    @Test
    void excludeOnlyFilter_rejectsAnyMatchingExcludeExtension() {
        ExtensionFilter filter = createFilter(List.of(), List.of(".md", ".txt"));
        assertFalse(filter.accepts("README.md"));
        assertFalse(filter.accepts("notes.txt"));
        assertTrue(filter.accepts("src/Main.java"));
    }

    // ---- Combined include + exclude ----

    @Test
    void combinedFilter_excludeTakesPrecedenceOverInclude() {
        // Include .java but also exclude .java — exclude wins
        ExtensionFilter filter = createFilter(List.of(".java"), List.of(".java"));
        assertFalse(filter.accepts("src/Main.java"));
    }

    @Test
    void combinedFilter_acceptsIncludedAndNotExcluded() {
        ExtensionFilter filter = createFilter(List.of(".java", ".md"), List.of(".md"));
        assertTrue(filter.accepts("src/Main.java"));
        assertFalse(filter.accepts("README.md"));
    }

    // ---- Empty include list means accept all (except excluded) ----

    @Test
    void emptyIncludeList_acceptsAllExtensions() {
        ExtensionFilter filter = createFilter(List.of(), List.of());
        assertTrue(filter.accepts("src/Main.java"));
        assertTrue(filter.accepts("README.md"));
        assertTrue(filter.accepts("data.csv"));
    }

    @Test
    void emptyIncludeList_stillAppliesExclude() {
        ExtensionFilter filter = createFilter(List.of(), List.of(".md"));
        assertTrue(filter.accepts("src/Main.java"));
        assertFalse(filter.accepts("README.md"));
    }
}
