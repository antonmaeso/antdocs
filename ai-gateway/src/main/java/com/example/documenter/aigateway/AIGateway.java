package com.example.documenter.aigateway;

import java.util.List;

import com.example.documenter.aigateway.domain.ConversationTurn;
import com.example.documenter.aigateway.domain.DocFileContent;
import com.example.documenter.aigateway.domain.DocFileSummary;
import com.example.documenter.aigateway.domain.RelevanceScanResult;
import com.example.documenter.aigateway.domain.ReviewDecision;

/**
 * Domain-oriented AI gateway that wraps Spring AI and exposes high-level methods
 * for documentation generation and PR review analysis.
 *
 * <p>Each method corresponds to a StringTemplate ({@code .st}) prompt file loaded from
 * the classpath at {@code prompts/}. The implementation substitutes named variables
 * into the template and sends the rendered prompt to the configured AI backend
 * (Ollama by default).</p>
 *
 * <p>Implementations should load all prompts via Spring AI's {@code PromptTemplate}
 * and validate that every {@code .st} file is present on the classpath at startup.</p>
 */
public interface AIGateway {

    /**
     * Generates Markdown documentation for a single source file.
     *
     * <p><b>Prompt template:</b> {@code generate-documentation.st}</p>
     * <p><b>Template variables:</b></p>
     * <ul>
     *   <li>{@code {filePath}} — the path of the source file relative to the repository root</li>
     *   <li>{@code {fileContent}} — the full text content of the source file</li>
     * </ul>
     *
     * @param fileContent the full text content of the source file
     * @param filePath    the path of the source file relative to the repository root
     * @return the generated Markdown documentation for the file
     */
    String generateDocumentation(String fileContent, String filePath);

    /**
     * Generates a top-level {@code README.md} for the repository based on summaries
     * of all documented files.
     *
     * <p><b>Prompt template:</b> {@code generate-readme.st}</p>
     * <p><b>Template variables:</b></p>
     * <ul>
     *   <li>{@code {repoName}} — the name of the repository</li>
     *   <li>{@code {fileSummaries}} — a formatted list of one-sentence summaries for each
     *       documented file</li>
     * </ul>
     *
     * @param fileSummaries the list of short descriptions (one per documented file)
     * @param repoName      the name of the repository
     * @return the generated Markdown content for the repository README
     */
    String generateRepositoryReadme(List<String> fileSummaries, String repoName);

    /**
     * Scans existing documentation files to determine which ones are relevant to a
     * pull-request diff.
     *
     * <p><b>Prompt template:</b> {@code scan-relevant-docs.st}</p>
     * <p><b>Template variables:</b></p>
     * <ul>
     *   <li>{@code {diffPatch}} — the unified diff patch of the pull request</li>
     *   <li>{@code {docSummaries}} — a formatted list of documentation file paths and their
     *       short descriptions</li>
     * </ul>
     *
     * @param diffPatch  the unified diff patch of the pull request
     * @param allDocFiles the list of all documentation file summaries (path + short description)
     * @return a {@link RelevanceScanResult} containing the paths of documentation files
     *         that are relevant to the diff
     */
    RelevanceScanResult scanRelevantDocs(String diffPatch, List<DocFileSummary> allDocFiles);

    /**
     * Analyses a pull-request diff against the relevant documentation and decides whether
     * the AI can update the docs autonomously or needs developer input.
     *
     * <p><b>Prompt template:</b> {@code analyse-diff.st}</p>
     * <p><b>Template variables:</b></p>
     * <ul>
     *   <li>{@code {diffPatch}} — the unified diff patch of the pull request</li>
     *   <li>{@code {relevantDocs}} — a formatted list of relevant documentation file paths
     *       and their full Markdown content</li>
     *   <li>{@code {history}} — the conversation history as a formatted list of
     *       question-and-reply exchanges from previous turns</li>
     * </ul>
     *
     * @param diffPatch    the unified diff patch of the pull request
     * @param relevantDocs the list of relevant documentation files with their full content
     * @param history      the conversation history from previous review turns (empty on first call)
     * @return a {@link ReviewDecision} — either {@link ReviewDecision.Autonomous} with a list
     *         of documentation updates, or {@link ReviewDecision.NeedsInput} with a clarifying
     *         question for the developer
     */
    ReviewDecision analyseAndDecide(String diffPatch, List<DocFileContent> relevantDocs,
                                    List<ConversationTurn> history);

    /**
     * Generates updated Markdown content for a single documentation file based on the
     * pull-request diff and conversation history.
     *
     * <p><b>Prompt template:</b> {@code analyse-diff.st} (or a dedicated update template)</p>
     * <p><b>Template variables:</b></p>
     * <ul>
     *   <li>{@code {originalContent}} — the current Markdown content of the documentation file</li>
     *   <li>{@code {diffPatch}} — the unified diff patch of the pull request</li>
     *   <li>{@code {history}} — the conversation history as a formatted list of
     *       question-and-reply exchanges</li>
     * </ul>
     *
     * @param originalContent the current Markdown content of the documentation file
     * @param diffPatch       the unified diff patch of the pull request
     * @param history         the conversation history from the review turns
     * @return the updated Markdown content for the documentation file
     */
    String generateUpdatedDocContent(String originalContent, String diffPatch,
                                     List<ConversationTurn> history);
}
