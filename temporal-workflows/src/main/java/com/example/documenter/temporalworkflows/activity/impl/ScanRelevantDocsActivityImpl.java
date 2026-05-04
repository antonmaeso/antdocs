package com.example.documenter.temporalworkflows.activity.impl;

import com.example.documenter.aigateway.AIGateway;
import com.example.documenter.aigateway.domain.DocFileSummary;
import com.example.documenter.aigateway.domain.RelevanceScanResult;
import com.example.documenter.temporalworkflows.activity.ScanRelevantDocsActivity;
import com.example.documenter.vcsgateway.VCSProvider;
import com.example.documenter.vcsgateway.domain.TreeEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of {@link ScanRelevantDocsActivity}.
 *
 * <p>Fetches the documentation file tree from the companion repository, builds
 * a list of {@link DocFileSummary} entries by reading each file's first sentence
 * as a short description, then delegates to the AI gateway to determine which
 * documentation files are relevant to the PR diff.</p>
 */
public class ScanRelevantDocsActivityImpl implements ScanRelevantDocsActivity {

    private final VCSProvider vcsProvider;
    private final AIGateway aiGateway;

    public ScanRelevantDocsActivityImpl(VCSProvider vcsProvider, AIGateway aiGateway) {
        this.vcsProvider = vcsProvider;
        this.aiGateway = aiGateway;
    }

    @Override
    public RelevanceScanResult scan(String diffPatch, String owner, String repo) {
        String companionRepo = repo + "_ai_documentation";
        List<TreeEntry> tree = vcsProvider.fetchFileTree(owner, companionRepo, "main");

        List<DocFileSummary> summaries = new ArrayList<>();
        for (TreeEntry entry : tree) {
            if (!"blob".equals(entry.type())) {
                continue;
            }
            if (!entry.path().endsWith(".md")) {
                continue;
            }
            try {
                String content = vcsProvider.fetchFileContent(owner, companionRepo, entry.path(), "main");
                String shortDescription = extractShortDescription(content);
                summaries.add(new DocFileSummary(entry.path(), shortDescription));
            } catch (Exception e) {
                // Log and continue — partial failures are acceptable
            }
        }

        return aiGateway.scanRelevantDocs(diffPatch, summaries);
    }

    /**
     * Extracts a short description from the documentation content.
     * Looks for a {@code <!-- description: ... -->} marker first, then falls back
     * to the first sentence of the content.
     */
    private String extractShortDescription(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        // Look for <!-- description: ... --> marker
        String marker = "<!-- description:";
        int markerIdx = content.indexOf(marker);
        if (markerIdx >= 0) {
            int start = markerIdx + marker.length();
            int end = content.indexOf("-->", start);
            if (end > start) {
                return content.substring(start, end).trim();
            }
        }

        // Fall back to first sentence
        String trimmed = content.strip();
        // Skip leading markdown headings
        for (String line : trimmed.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int dotIdx = stripped.indexOf('.');
            if (dotIdx > 0 && dotIdx < stripped.length() - 1) {
                return stripped.substring(0, dotIdx + 1);
            }
            return stripped;
        }
        return trimmed.substring(0, Math.min(trimmed.length(), 100));
    }
}
