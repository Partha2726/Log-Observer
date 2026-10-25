package com.ielop.platform.assistant;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redaction before every AI call (section 11.3, NFR-05). In order: secrets ({@code password=…},
 * bearer tokens, cookies, Authorization headers), e-mail addresses, external IPv4 addresses
 * (outside {@code app.ai.internal-cidrs}) as {@code ext-ip-<hash4>}, account- or card-like 9–18 digit
 * runs as {@code ACC-****1234}, and phone numbers. The same input always gives the same output.
 */
@Component
public class Redactor {

    static final Pattern SECRET = Pattern.compile("(?i)\\b(password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key)(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s&,;\"']+)");
    static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
    static final Pattern AUTH_HEADER = Pattern.compile("(?i)\\b(authorization|proxy-authorization)(\\s*[=:]\\s*)(?:(?:bearer|basic|digest|token)\\s+)?[^\\s,;&]+");
    static final Pattern COOKIE = Pattern.compile("(?i)\\b(set-cookie|cookie)(\\s*[=:]\\s*)[^\\r\\n]+");
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    static final Pattern IPV4 = Pattern.compile("(?<![\\d.])((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})(?![\\d.])");
    static final Pattern ACCOUNT = Pattern.compile("(?<!\\d)\\d{9,18}(?!\\d)");
    static final Pattern PHONE = Pattern.compile("(?<![\\w-])\\+?\\(?\\d{1,4}\\)?(?:[ .-]\\(?\\d{2,4}\\)?){2,4}(?![\\w-])");

    private final List<Cidr> internal;

    public Redactor(AiProperties props) {
        this.internal = new ArrayList<>();
        for (String c : props.getInternalCidrs()) {
            internal.add(Cidr.parse(c.trim()));
        }
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = SECRET.matcher(text).replaceAll("$1$2[redacted]");
        s = BEARER.matcher(s).replaceAll("Bearer [redacted]");
        s = AUTH_HEADER.matcher(s).replaceAll("$1$2[redacted]");
        s = COOKIE.matcher(s).replaceAll("$1$2[redacted]");
        s = EMAIL.matcher(s).replaceAll("[email]");
        s = replace(IPV4, s, m -> isInternal(m.group(1)) ? m.group(1) : "ext-ip-" + hash4(m.group(1)));
        s = replace(ACCOUNT, s, m -> "ACC-****" + m.group().substring(m.group().length() - 4));
        s = replace(PHONE, s, m -> phoneLike(m.group()) ? "[phone]" : m.group());
        return s;
    }

    /** A phone match needs at least 7 digits and must not be a timestamp, a version or an already-masked value. */
    private static boolean phoneLike(String m) {
        long digits = m.chars().filter(Character::isDigit).count();
        return digits >= 7 && digits <= 15 && !m.contains(":") && (m.startsWith("+") || m.contains("(") || m.contains(" ") || m.contains("-"))
                && !m.matches("\\d{4}-\\d{2}-\\d{2}.*");
    }

    boolean isInternal(String ip) {
        try {
            byte[] addr = InetAddress.getByName(ip).getAddress();
            return internal.stream().anyMatch(c -> c.contains(addr));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static String hash4(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String replace(Pattern p, String s, java.util.function.Function<Matcher, String> f) {
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(f.apply(m)));
        }
        m.appendTail(out);
        return out.toString();
    }

    record Cidr(BigInteger network, BigInteger mask, int length) {
        static Cidr parse(String cidr) {
            String[] parts = cidr.split("/");
            try {
                byte[] addr = InetAddress.getByName(parts[0]).getAddress();
                int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : addr.length * 8;
                BigInteger mask = BigInteger.ONE.shiftLeft(addr.length * 8).subtract(BigInteger.ONE)
                        .xor(BigInteger.ONE.shiftLeft(addr.length * 8 - bits).subtract(BigInteger.ONE));
                return new Cidr(new BigInteger(1, addr).and(mask), mask, addr.length);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalStateException("app.ai.internal-cidrs has an invalid entry: " + cidr, e);
            }
        }

        boolean contains(byte[] addr) {
            return addr.length == length && new BigInteger(1, addr).and(mask).equals(network);
        }
    }
}
