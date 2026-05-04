package com.example.documenter.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for the target repository to crawl and document.
 * Bound from the {@code target-repository.*} namespace in application.yml.
 */
@ConfigurationProperties(prefix = "target-repository")
@Validated
public class TargetRepositoryProperties {

    @NotBlank
    private String owner;

    @NotBlank
    private String repo;

    @NotBlank
    private String ref = "main";

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRepo() {
        return repo;
    }

    public void setRepo(String repo) {
        this.repo = repo;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }
}
