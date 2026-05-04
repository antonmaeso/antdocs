package com.example.testonly;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.example.documenter.aigateway.config.AIGatewayConfig;
import com.example.documenter.aigateway.config.AIProperties;

/**
 * Minimal Spring Boot application that loads only the AI gateway configuration
 * validation. Used to test that missing or invalid AI config causes a fail-fast
 * startup error in isolation from other modules.
 */
@SpringBootApplication(scanBasePackages = "com.example.testonly.noop")
@EnableConfigurationProperties(AIProperties.class)
public class AIConfigValidationTestApp {

    @Bean
    AIGatewayConfig aiGatewayConfig(AIProperties aiProperties) {
        return new AIGatewayConfig(aiProperties);
    }
}
