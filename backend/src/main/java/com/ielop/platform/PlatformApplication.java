package com.ielop.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the AI-Assisted Log Analysis & Incident Investigation
 * Platform backend.
 *
 * <p>This is a modular monolith. Implemented modules and future boundaries
 * live as sibling packages under {@code com.ielop.platform} and are picked up
 * by Spring's component scan rooted at this class.</p>
 */
@SpringBootApplication
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
