package com.ielop.platform.trace.assembly;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.trace.TraceProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Trace configuration hash (section 7.2): SHA-256 over the assembly version, every
 * {@code app.trace.*} value and the flow definition file's SHA-256. It changes when
 * any of them changes, and only then (TRACE-015).
 */
public final class ConfigHash {

    private ConfigHash() {
    }

    public static String of(int assemblyVersion, TraceProperties p, FlowDefinition def) {
        String canonical = String.format(Locale.ROOT,
                "assemblyVersion=%d;windowPadMs=%d;threadWindowMs=%d;timeOnlyWindowMs=%d;minConfidence=%s;noiseThreads=%s;flow=%s:%s",
                assemblyVersion, p.windowPadMs(), p.threadWindowMs(), p.timeOnlyWindowMs(),
                java.math.BigDecimal.valueOf(p.minConfidence()).stripTrailingZeros().toPlainString(),
                p.noiseThreads(), def.id(), def.sha256());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
