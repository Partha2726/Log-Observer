package com.ielop.platform.assistant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that provides tools to the assistant (section 11.6). Such classes are read-only:
 * ARCH-004 checks that they never call a repository's save or delete methods or a JDBC update.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AssistantTool {
}
