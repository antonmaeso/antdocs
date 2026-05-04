package com.example.documenter.aigateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import com.example.documenter.aigateway.domain.ConversationTurn;
import com.example.documenter.aigateway.domain.DocFileContent;
import com.example.documenter.aigateway.domain.DocFileSummary;
import com.example.documenter.aigateway.domain.DocUpdate;
import com.example.documenter.aigateway.domain.RelevanceScanResult;
import com.example.documenter.aigateway.domain.ReviewDecision;

import jakarta.annotation.PostConstruct;

/**
 * Spring AI-backed implementation of {@link AIGateway}.
 *
 * <p>Each method loads a {@code .st} prompt template via {@link PromptLoader},
 * substitutes the required variables, sends the rendered prompt to the configured
 * AI backend (Ollama by default), and parses the response into the appropriate
 * domain type.</p>
 *
 * <p>At startup, all five required prompt template files are validated to be present
 * on the classpath. If any file is missing, the application fails fast with an
 * {@link IllegalStateException}.</p>
 */
@Service
public class SpringAIGateway implements AIGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAIGateway.class);

    private static final String GENERATE_DOCUMENTATION_TEMPLATE = "generate-documentation.st";
    private static final String GENERATE_README_TEMPLATE = "generate-readme.st";
    private static final String SCAN_RELEVANT_DOCS_TEMPLATE = "scan-relevant-docs.st";
    private static final String ANALYSE_DIFF_TEMPLATE = "analyse-diff.st";
    private static final String PR_TIMEOUT_FOLLOWUP_TEMPLATE = "pr-timeout-followup.st";

    static final List<String> REQUIRED_TEMPLATES = List.of(
            GENERATE_DOCUMENTATION_TEMPLATE,
            GENERATE_README_TEMPLATE,
            SCAN_RELEVANT_DOCS_TEMPLATE,
            ANALYSE_DIFF_TEMPLATE,
            PR_TIMEOUT_FOLLOWUP_TEMPLATE
    );

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public SpringAIGateway(ChatClient chatClient, PromptLoader promptLoader) {
        this.chatClient = chatClient;
        this.promptLoader = promptLoader;
    }

    /**
     * Validates that all required prompt template files are present on the classpath.
     * Fails fast with an {@link IllegalStateException} if any file is missing.
     */
    @PostConstruct
    void validatePromptTemplates() {
        for (String template : REQUIRED_TEMPLATES) {
            if (!promptLoader.exists(template)) {
                throw new IllegalStateException(
                        "Required prompt template file missing from classpath: prompts/" + template);
            }
        }
        log.info("All {} prompt template files validated successfully", REQUIRED_TEMPLATES.size());
    }

    @Override
    public String generateDocumentation(String fileContent, String filePath) {
        String rendered = promptLoader.load(GENERATE_DOCUMENTATION_TEMPLATE, Map.of(
                "filePath", filePath,
                "fileContent", fileContent
        ));
        return callAI(rendered);
    }

    @Override
    public String generateRepositoryReadme(List<String> fileSummaries, String repoName) {
        String summariesText = String.join("\n", fileSummaries);
        String rendered = promptLoader.load(GENERATE_README_TEMPLATE, Map.of(
                "repoName", repoName,
                "fileSummaries", summariesText
        ));
        return callAI(rendered);
    }

    @Override
    public RelevanceScanResult scanRelevantDocs(String diffPatch, List<DocFileSummary> allDocFiles) {
        String docSummariesText = allDocFiles.stream()
                .map(doc -> doc.path() + ": " + doc.shortDescription())
                .collect(Collectors.joining("\n"));

        String rendered = promptLoader.load(SCAN_RELEVANT_DOCS_TEMPLATE, Map.of(
                "diffPatch", diffPatch,
                "docSummaries", docSummariesText
        ));

        String response = callAI(rendered);
        List<String> paths = parseJsonArrayOfStrings(response);
        return new RelevanceScanResult(paths);
    }

    @Override
    public ReviewDecision analyseAndDecide(String diffPatch, List<DocFileContent> relevantDocs,
                                           List<ConversationTurn> history) {
        String relevantDocsText = relevantDocs.stream()
                .map(doc -> "--- " + doc.path() + " ---\n" + doc.markdownContent())
                .collect(Collectors.joining("\n\n"));

        String historyText = history.stream()
                .map(turn -> "Q: " + turn.question() + "\nA: " + turn.reply())
                .collect(Collectors.joining("\n\n"));

        String rendered = promptLoader.load(ANALYSE_DIFF_TEMPLATE, Map.of(
                "diffPatch", diffPatch,
                "relevantDocs", relevantDocsText,
                "history", historyText
        ));

        String response = callAI(rendered);
        return parseReviewDecision(response);
    }

    @Override
    public String generateUpdatedDocContent(String originalContent, String diffPatch,
                                            List<ConversationTurn> history) {
        String historyText = history.stream()
                .map(turn -> "Q: " + turn.question() + "\nA: " + turn.reply())
                .collect(Collectors.joining("\n\n"));

        String rendered = promptLoader.load(ANALYSE_DIFF_TEMPLATE, Map.of(
                "diffPatch", diffPatch,
                "relevantDocs", originalContent,
                "history", historyText
        ));

        return callAI(rendered);
    }

    /**
     * Sends a rendered prompt to the AI backend and returns the text response.
     */
    private String callAI(String renderedPrompt) {
        ChatResponse response = chatClient.call(new Prompt(renderedPrompt));
        return response.getResult().getOutput().getContent();
    }

    /**
     * Parses the AI response as a JSON array of strings (file paths).
     * Handles both clean JSON arrays and responses with surrounding text.
     */
    List<String> parseJsonArrayOfStrings(String response) {
        List<String> paths = new ArrayList<>();
        String trimmed = response.trim();

        // Find the JSON array in the response
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start == -1 || end == -1 || end <= start) {
            log.warn("Could not parse JSON array from AI response: {}", trimmed);
            return paths;
        }

        String arrayContent = trimmed.substring(start + 1, end).trim();
        if (arrayContent.isEmpty()) {
            return paths;
        }

        // Split by comma and clean up each entry
        for (String entry : arrayContent.split(",")) {
            String cleaned = entry.trim()
                    .replaceAll("^\"|\"$", "")  // remove surrounding quotes
                    .replaceAll("^'|'$", "")    // remove surrounding single quotes
                    .trim();
            if (!cleaned.isEmpty()) {
                paths.add(cleaned);
            }
        }
        return paths;
    }

    /**
     * Parses the AI response into a {@link ReviewDecision}.
     *
     * <p>Expected response formats:</p>
     * <ul>
     *   <li>{@code AUTONOMOUS: [{"path":"...","newContent":"..."},...]}</li>
     *   <li>{@code QUESTION: <question text>}</li>
     * </ul>
     */
    ReviewDecision parseReviewDecision(String response) {
        String trimmed = response.trim();

        if (trimmed.startsWith("AUTONOMOUS:")) {
            String jsonPart = trimmed.substring("AUTONOMOUS:".length()).trim();
            List<DocUpdate> updates = parseDocUpdates(jsonPart);
            return new ReviewDecision.Autonomous(updates);
        } else if (trimmed.startsWith("QUESTION:")) {
            String question = trimmed.substring("QUESTION:".length()).trim();
            return new ReviewDecision.NeedsInput(question);
        } else {
            // Default: treat as needing input if we can't parse
            log.warn("Unexpected ReviewDecision format, treating as NeedsInput: {}",
                    trimmed.substring(0, Math.min(trimmed.length(), 200)));
            return new ReviewDecision.NeedsInput(trimmed);
        }
    }

    /**
     * Parses a JSON array of doc update objects.
     * Expected format: [{"path":"...","newContent":"..."},...]
     */
    private List<DocUpdate> parseDocUpdates(String json) {
        List<DocUpdate> updates = new ArrayList<>();
        String trimmed = json.trim();

        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start == -1 || end == -1 || end <= start) {
            log.warn("Could not parse doc updates JSON array: {}", trimmed);
            return updates;
        }

        String arrayContent = trimmed.substring(start + 1, end).trim();
        if (arrayContent.isEmpty()) {
            return updates;
        }

        // Simple JSON object parsing — split by },{ pattern
        // This handles the common case of well-formed JSON from the AI
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    objStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = arrayContent.substring(objStart, i + 1);
                    DocUpdate update = parseDocUpdateObject(obj);
                    if (update != null) {
                        updates.add(update);
                    }
                    objStart = -1;
                }
            }
        }

        return updates;
    }

    /**
     * Parses a single JSON object into a {@link DocUpdate}.
     */
    private DocUpdate parseDocUpdateObject(String json) {
        String path = extractJsonStringField(json, "path");
        String newContent = extractJsonStringField(json, "newContent");
        if (path == null || newContent == null) {
            log.warn("Could not parse DocUpdate from JSON: {}", json);
            return null;
        }
        return new DocUpdate(path, newContent);
    }

    /**
     * Extracts a string field value from a simple JSON object.
     */
    private String extractJsonStringField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) {
            return null;
        }

        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex == -1) {
            return null;
        }

        // Find the opening quote of the value
        int valueStart = json.indexOf('"', colonIndex + 1);
        if (valueStart == -1) {
            return null;
        }

        // Find the closing quote, handling escaped quotes
        StringBuilder value = new StringBuilder();
        for (int i = valueStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') {
                    value.append('"');
                    i++;
                } else if (next == 'n') {
                    value.append('\n');
                    i++;
                } else if (next == 't') {
                    value.append('\t');
                    i++;
                } else if (next == '\\') {
                    value.append('\\');
                    i++;
                } else {
                    value.append(c);
                }
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        return null;
    }
}
