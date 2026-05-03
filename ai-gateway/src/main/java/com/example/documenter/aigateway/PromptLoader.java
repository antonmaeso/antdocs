package com.example.documenter.aigateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads {@code .st} prompt template files from the classpath and substitutes
 * named variables using simple string replacement.
 *
 * <p>Templates are expected under {@code prompts/} on the classpath. Variables
 * in the template use the {@code {variableName}} syntax and are replaced with
 * the corresponding values from the supplied variable map.</p>
 */
@Component
public class PromptLoader {

    private static final String PROMPTS_DIR = "prompts/";

    /**
     * Loads a prompt template file from the classpath and substitutes the given variables.
     *
     * @param templateFileName the name of the {@code .st} file (e.g. {@code "generate-documentation.st"})
     * @param variables        a map of variable names to their replacement values
     * @return the rendered prompt string with all variables substituted
     * @throws IllegalArgumentException if the template file cannot be found on the classpath
     * @throws IllegalStateException    if the template file cannot be read
     */
    public String load(String templateFileName, Map<String, String> variables) {
        String template = loadRawTemplate(templateFileName);
        return substitute(template, variables);
    }

    /**
     * Checks whether a prompt template file exists on the classpath.
     *
     * @param templateFileName the name of the {@code .st} file
     * @return {@code true} if the file exists, {@code false} otherwise
     */
    public boolean exists(String templateFileName) {
        return new ClassPathResource(PROMPTS_DIR + templateFileName).exists();
    }

    /**
     * Loads the raw template content from the classpath without any variable substitution.
     *
     * @param templateFileName the name of the {@code .st} file
     * @return the raw template content
     */
    String loadRawTemplate(String templateFileName) {
        ClassPathResource resource = new ClassPathResource(PROMPTS_DIR + templateFileName);
        if (!resource.exists()) {
            throw new IllegalArgumentException(
                    "Prompt template file not found on classpath: " + PROMPTS_DIR + templateFileName);
        }
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read prompt template file: " + PROMPTS_DIR + templateFileName, e);
        }
    }

    private String substitute(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
