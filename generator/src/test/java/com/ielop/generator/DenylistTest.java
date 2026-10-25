package com.ielop.generator;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GEN-009: no deny-listed name appears anywhere in the repository. The list
 * comes from the untracked {@code .denylist} file at the repository root (one
 * term per line, {@code #} comments) or the {@code DENYLIST} environment
 * variable (comma or newline separated). Without either, the check is skipped
 * with a warning.
 */
class DenylistTest {

    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target", "dist", "data", ".idea", ".vscode");

    @Test
    void repositoryContainsNoDenylistedName() throws IOException {
        Path root = GeneratorRun.repoRoot();
        List<String> terms = terms(root);
        if (terms.isEmpty()) {
            System.err.println("WARNING GEN-009 skipped: no .denylist file and no DENYLIST variable");
        }
        Assumptions.assumeFalse(terms.isEmpty(), "GEN-009 skipped: no deny-list available");

        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p) && !skipped(root, p)).toList()) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                String name = root.relativize(file).toString().toLowerCase(Locale.ROOT);
                for (String term : terms) {
                    if (text.contains(term) || name.contains(term)) {
                        hits.add(root.relativize(file) + " contains a deny-listed term");
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), () -> String.join("\n", hits));
    }

    private static boolean skipped(Path root, Path file) {
        Path rel = root.relativize(file);
        if (rel.toString().equals(".denylist")) {
            return true;
        }
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> terms(Path root) throws IOException {
        List<String> raw = new ArrayList<>();
        Path file = root.resolve(".denylist");
        if (Files.isRegularFile(file)) {
            raw.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        }
        String env = System.getenv("DENYLIST");
        if (env != null) {
            raw.addAll(List.of(env.split("[,\\n]")));
        }
        return raw.stream()
                .map(String::trim)
                .filter(t -> !t.isEmpty() && !t.startsWith("#"))
                .map(t -> t.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
