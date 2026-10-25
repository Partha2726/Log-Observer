package com.ielop.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the generator in a fresh JVM. Log4j2 is configured once per JVM (the
 * output root is a system property), so each run needs its own process.
 */
final class GeneratorRun {

    static final Path FLOW_DEFINITION = repoRoot().resolve("config/flows/purchase-request.yaml");

    private GeneratorRun() {
    }

    static Path run(Path dir, String... extraArgs) throws IOException, InterruptedException {
        Path out = dir.resolve("logs");
        Path truth = dir.resolve("ground-truth");
        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                GeneratorMain.class.getName(),
                "--out", out.toString(), "--truth", truth.toString(),
                "--flow-definition", FLOW_DEFINITION.toString()));
        cmd.addAll(List.of(extraArgs));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
            throw new IllegalStateException("Generator failed:\n" + output);
        }
        return dir;
    }

    /** SHA-256 over every file's relative path and bytes, in sorted order. */
    static String treeHash(Path root) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path file : files(root)) {
                md.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                md.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Path> files(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    static List<String> lines(Path root, String folder) throws IOException {
        List<String> all = new ArrayList<>();
        for (Path f : files(root.resolve(folder))) {
            all.addAll(Files.readAllLines(f, StandardCharsets.UTF_8));
        }
        return all;
    }

    static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("config/flows"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("Repository root (with config/flows) not found");
        }
        return p;
    }
}
