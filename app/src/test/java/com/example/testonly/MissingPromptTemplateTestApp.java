package com.example.testonly;

import org.springframework.ai.chat.ChatClient;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.example.documenter.aigateway.PromptLoader;
import com.example.documenter.aigateway.SpringAIGateway;
import com.example.documenter.aigateway.config.AIGatewayConfig;
import com.example.documenter.aigateway.config.AIProperties;

/**
 * Minimal Spring Boot application that loads only the AI gateway components
 * with a {@link PromptLoader} that always reports templates as missing.
 * This triggers the {@link SpringAIGateway} {@code @PostConstruct} validation
 * to fail fast with a descriptive error.
 *
 * <p>Placed in {@code com.example.testonly} to avoid being picked up by
 * {@link com.example.documenter.DocumenterApplication}'s component scan.</p>
 */
@SpringBootApplication(scanBasePackages = "com.example.testonly.noop")
@EnableConfigurationProperties(AIProperties.class)
public class MissingPromptTemplateTestApp {

    @Bean
    PromptLoader promptLoader() {
        return new PromptLoader() {
            @Override
            public boolean exists(String templateFileName) {
                return false;
            }
        };
    }

    @Bean
    ChatClient chatClient() {
        // Will never be used — startup validation fails before any AI call
        return null;
    }

    @Bean
    SpringAIGateway springAIGateway(ChatClient chatClient, PromptLoader promptLoader) {
        return new SpringAIGateway(chatClient, promptLoader);
    }

    @Bean
    AIGatewayConfig aiGatewayConfig(AIProperties aiProperties) {
        return new AIGatewayConfig(aiProperties);
    }
}
