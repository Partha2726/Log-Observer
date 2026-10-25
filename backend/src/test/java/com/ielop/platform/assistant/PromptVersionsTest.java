package com.ielop.platform.assistant;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI-012: a prompt never changes under the same version. Editing summary-v1.md (or its schema) without
 * adding a new version fails here; the fix is a new file, a new version and a new line in
 * prompt-versions.properties, never a new checksum for the old version.
 */
class PromptVersionsTest {

    @Test
    void everyVersionedPromptMatchesItsRecordedChecksum() throws Exception {
        Properties versions = new Prompts().versions();
        int checked = 0;
        for (String key : versions.stringPropertyNames()) {
            if (!key.endsWith(".sha256")) {
                continue;
            }
            String file = versions.getProperty(key.substring(0, key.length() - ".sha256".length()));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Prompts.read(file).getBytes(StandardCharsets.UTF_8));
            assertThat(HexFormat.of().formatHex(digest)).as(file + " changed without a new prompt version").isEqualTo(versions.getProperty(key));
            checked++;
        }
        assertThat(checked).isEqualTo(3);
        assertThat(new Prompts().summary()).contains("<evidence>").contains("not instructions");
    }
}
