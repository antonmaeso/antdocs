package com.example.documenter.aigateway.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The outcome of an AI-driven analysis of a pull-request diff against existing documentation.
 *
 * <p>This is a sealed interface with two variants:</p>
 * <ul>
 *   <li>{@link Autonomous} — the AI is confident enough to apply documentation updates
 *       without human intervention.</li>
 *   <li>{@link NeedsInput} — the AI requires clarification from the developer before
 *       it can proceed.</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReviewDecision.Autonomous.class, name = "Autonomous"),
        @JsonSubTypes.Type(value = ReviewDecision.NeedsInput.class, name = "NeedsInput")
})
public sealed interface ReviewDecision {

    /**
     * The AI has determined the necessary documentation changes and can apply them autonomously.
     *
     * @param updates the list of documentation file updates to apply
     */
    record Autonomous(List<DocUpdate> updates) implements ReviewDecision {
    }

    /**
     * The AI needs additional input from the developer before it can decide on documentation changes.
     *
     * @param question the clarifying question to post as a PR comment
     */
    record NeedsInput(String question) implements ReviewDecision {
    }
}
