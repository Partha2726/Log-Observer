package com.ielop.generator.emit;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.SplittableRandom;

/** Generated identifiers in the formats of spec section 2.2. */
public final class Ids {

    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Ids() {
    }

    /** Random UUID v4 drawn from {@code rnd}, so it is reproducible for a seed. */
    public static String uuid(SplittableRandom rnd) {
        long hi = (rnd.nextLong() & 0xffffffffffff0fffL) | 0x0000000000004000L;
        long lo = (rnd.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return hex(hi >>> 32, 8) + "-" + hex(hi >>> 16, 4) + "-" + hex(hi, 4) + "-" + hex(lo >>> 48, 4) + "-" + hex(lo, 12);
    }

    public static String sessionId(SplittableRandom rnd) {
        return "sess_" + hex(rnd.nextLong(), 16);
    }

    public static String externalCorrelationId(SplittableRandom rnd) {
        return "pp-" + hex(rnd.nextLong(), 12);
    }

    /** {@code REQ-yyyyMMdd-NNNNNN}. */
    public static String flowRef(LocalDate date, int sequence) {
        return "REQ-" + REF_DATE.format(date) + "-" + String.format("%06d", sequence);
    }

    public static String hex(long value, int digits) {
        char[] out = new char[digits];
        for (int i = digits - 1; i >= 0; i--) {
            out[i] = HEX[(int) (value & 0xf)];
            value >>>= 4;
        }
        return new String(out);
    }
}
