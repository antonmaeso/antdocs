package com.example.documenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.example.documenter",
        "com.example.documenter.crawler",
        "com.example.documenter.documentation",
        "com.example.documenter.prreviewer",
        "com.example.documenter.aigateway",
        "com.example.documenter.vcsgateway",
        "com.example.documenter.temporalworkflows"
})
@ConfigurationPropertiesScan({
        "com.example.documenter.config",
        "com.example.documenter.vcsgateway.config",
        "com.example.documenter.temporalworkflows.config",
        "com.example.documenter.crawler.config",
        "com.example.documenter.aigateway.config"
})
@EnableScheduling
public class DocumenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumenterApplication.class, args);
    }
}
