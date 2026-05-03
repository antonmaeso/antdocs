package com.example.documenter.aigateway.domain;

import java.util.List;

/**
 * The result of scanning existing documentation files for relevance to a pull-request diff.
 *
 * <p>Contains the paths of documentation files that the AI determined are affected by
 * the changes in the diff and may need to be updated.</p>
 *
 * @param relevantPaths the list of documentation file paths relevant to the diff
 */
public record RelevanceScanResult(List<String> relevantPaths) {
}
