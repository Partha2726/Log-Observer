package com.ielop.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** GEN-001: two runs with the same seed produce byte-identical output; another seed does not. */
class DeterminismTest {

    private static final String[] ARGS = {"--flows", "40", "--days", "1", "--seed", "42", "--legacy-timezone", "Asia/Kolkata"};

    @Test
    void sameSeedGivesByteIdenticalTrees(@TempDir Path a, @TempDir Path b) throws Exception {
        GeneratorRun.run(a, ARGS);
        GeneratorRun.run(b, ARGS);

        assertFalse(GeneratorRun.files(a).isEmpty());
        assertEquals(GeneratorRun.treeHash(a), GeneratorRun.treeHash(b));
    }

    @Test
    void differentSeedGivesDifferentOutput(@TempDir Path a, @TempDir Path b) throws Exception {
        GeneratorRun.run(a, ARGS);
        GeneratorRun.run(b, "--flows", "40", "--days", "1", "--seed", "7", "--legacy-timezone", "Asia/Kolkata");

        assertNotEquals(GeneratorRun.treeHash(a), GeneratorRun.treeHash(b));
    }
}
