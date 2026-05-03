package com.example.documenter.aigateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Configuration class for the AI Gateway module.
 *
 * <p>Enables binding of {@code spring.ai.ollama.*} properties and validates
 * that the required AI configuration is present at startup.</p>
 */
@Configuration
@EnableConfigurationProperties(AIProperties.class)
public class AIGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(AIGatewayConfig.class);

    private final AIProperties aiProperties;

    public AIGatewayConfig(AIProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @PostConstruct
    void validateAIConfiguration() {
        if (aiProperties.getBaseUrl() == null || aiProperties.getBaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "AI configuration is missing or invalid: spring.ai.ollama.base-url must be configured");
        }
        if (aiProperties.getChat() == null
                || aiProperties.getChat().getModel() == null
                || aiProperties.getChat().getModel().isBlank()) {
            throw new IllegalStateException(
                    "AI configuration is missing or invalid: spring.ai.ollama.chat.model must be configured");
        }
        log.info("AI configuration validated: base-url={}, model={}",
                aiProperties.getBaseUrl(), aiProperties.getChat().getModel());
    }
}
