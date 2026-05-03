package com.example.documenter.temporalworkflows.domain;

/**
 * A single question-and-reply exchange between the AI reviewer and a developer
 * during a PR review conversation.
 *
 * @param question the clarifying question posted by the AI reviewer
 * @param reply    the developer's response to the question
 */
public record ConversationTurn(String question, String reply) {
}
