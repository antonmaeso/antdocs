package com.example.documenter.aigateway.domain;

/**
 * A single question-and-reply exchange between the AI reviewer and a developer
 * during a PR review conversation.
 *
 * <p>This is a local copy of the temporal-workflows module's {@code ConversationTurn} record,
 * kept separate to avoid a cross-module dependency. Both modules use the same shape
 * and values are mapped at the boundary between modules.</p>
 *
 * @param question the clarifying question posted by the AI reviewer
 * @param reply    the developer's response to the question
 */
public record ConversationTurn(String question, String reply) {
}
