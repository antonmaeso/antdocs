package com.example.documenter.crawler.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for the repository crawler.
 * Bound from the {@code crawler.*} namespace in application.yml.
 */
@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {

    private List<String> includeExtensions = List.of();
    private List<String> excludeExtensions = List.of();

    public List<String> getIncludeExtensions() {
        return includeExtensions;
    }

    public void setIncludeExtensions(List<String> includeExtensions) {
        this.includeExtensions = includeExtensions;
    }

    public List<String> getExcludeExtensions() {
        return excludeExtensions;
    }

    public void setExcludeExtensions(List<String> excludeExtensions) {
        this.excludeExtensions = excludeExtensions;
    }
}
