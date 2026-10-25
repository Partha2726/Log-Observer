package com.ielop.platform.assistant;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Versioned prompts from {@code resources/prompts} (sections 11.4 and 11.10). The summary prompt is
 * {@code sum-1}; its text and JSON schema are fixed files, listed with their SHA-256 sums in
 * {@code prompt-versions.properties}.
 */
@Component
public class Prompts {

    public static final String SUMMARY_VERSION = "sum-1";
    public static final String CHAT_VERSION = "chat-1";

    private final Properties versions = new Properties();
    private final String summary;
    private final String summarySchema;
    private final String assistant;

    public Prompts() {
        try (InputStream in = new ClassPathResource("prompts/prompt-versions.properties").getInputStream()) {
            versions.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.summary = read(versions.getProperty(SUMMARY_VERSION + ".prompt"));
        this.summarySchema = read(versions.getProperty(SUMMARY_VERSION + ".schema"));
        this.assistant = read(versions.getProperty(CHAT_VERSION + ".prompt"));
    }

    public String assistant() {
        return assistant;
    }

    public String summary() {
        return summary;
    }

    public String summarySchema() {
        return summarySchema;
    }

    public Properties versions() {
        return versions;
    }

    static String read(String file) {
        try (InputStream in = new ClassPathResource("prompts/" + file).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
