package com.example.documenter.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for the PR polling schedule.
 * Bound from the {@code polling.*} namespace in application.yml.
 */
@ConfigurationProperties(prefix = "polling")
@Validated
public class PollingProperties {

    @Min(1)
    private int intervalSeconds = 60;

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }
}
