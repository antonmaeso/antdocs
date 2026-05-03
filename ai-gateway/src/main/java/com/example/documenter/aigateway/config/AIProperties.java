package com.example.documenter.aigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Configuration properties for the AI gateway, bound to {@code spring.ai.ollama.*}.
 *
 * <p>Validates that the required Ollama configuration is present at startup.
 * If any required property is missing, the application fails fast with a
 * descriptive error message.</p>
 */
@Validated
@ConfigurationProperties(prefix = "spring.ai.ollama")
public class AIProperties {

    /**
     * Base URL of the Ollama server (e.g. {@code http://localhost:11434}).
     */
    @NotBlank(message = "spring.ai.ollama.base-url must be configured")
    private String baseUrl;

    /**
     * Chat-specific configuration.
     */
    private Chat chat = new Chat();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public static class Chat {

        /**
         * The model name to use for chat completions (e.g. {@code llama3}).
         */
        @NotBlank(message = "spring.ai.ollama.chat.model must be configured")
        private String model;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
