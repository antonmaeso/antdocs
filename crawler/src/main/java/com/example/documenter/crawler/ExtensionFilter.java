package com.example.documenter.crawler;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.documenter.crawler.config.CrawlerProperties;

/**
 * Filters file paths based on configured include and exclude extension lists.
 *
 * <p>If the include list is non-empty, a file must match one of the include extensions
 * to be accepted. The exclude list is always applied: if a file matches any exclude
 * extension, it is rejected regardless of the include list.
 */
@Component
public class ExtensionFilter {

    private final List<String> includeExtensions;
    private final List<String> excludeExtensions;

    public ExtensionFilter(CrawlerProperties properties) {
        this.includeExtensions = properties.getIncludeExtensions();
        this.excludeExtensions = properties.getExcludeExtensions();
    }

    /**
     * Tests whether the given file path is accepted by the configured extension filter.
     *
     * @param filePath the file path to test
     * @return {@code true} if the file passes the filter
     */
    public boolean accepts(String filePath) {
        // Exclude list always applied: reject if file matches any exclude extension
        for (String ext : excludeExtensions) {
            if (filePath.endsWith(ext)) {
                return false;
            }
        }

        // If include list is non-empty, file must match one of the include extensions
        if (!includeExtensions.isEmpty()) {
            for (String ext : includeExtensions) {
                if (filePath.endsWith(ext)) {
                    return true;
                }
            }
            return false;
        }

        // Empty include list means accept all (that weren't excluded)
        return true;
    }
}
