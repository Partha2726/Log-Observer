package com.ielop.platform.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Redaction before every AI call (T25: AI-008 unit part, section 11.3). Generated values only. */
class RedactorTest {

    private final Redactor redactor = new Redactor(new AiProperties());

    @Test
    void accountAndCardLikeNumbersKeepOnlyTheirLastFourDigits() {
        assertThat(redactor.redact("Payout to account 999912345678 failed")).isEqualTo("Payout to account ACC-****5678 failed");
        assertThat(redactor.redact("card 4000123412341234")).isEqualTo("card ACC-****1234");
        assertThat(redactor.redact("ids 12345678 and 1234567890123456789")).as("8 and 19 digits are not accounts")
                .isEqualTo("ids 12345678 and 1234567890123456789");
        assertThat(redactor.redact("already ACC-****5678")).isEqualTo("already ACC-****5678");
    }

    @Test
    void emailsPhonesAndSecretsAreRemoved() {
        String out = redactor.redact("notify finance.team@acme.example or +1 555 010 9999; password=hunter2 token: abc.def "
                + "api_key=\"k-123\" Authorization: Bearer eyJhbGciOi.payload.sig Cookie: SESSION=abc123");
        assertThat(out).doesNotContain("finance.team@acme.example", "555 010 9999", "hunter2", "abc.def", "k-123", "eyJhbGciOi", "SESSION=abc123")
                .contains("[email]", "[phone]", "password=[redacted]", "token: [redacted]", "api_key=[redacted]", "Authorization: [redacted]",
                        "Cookie: [redacted]");
        assertThat(redactor.redact("GET /cb?access_key=XYZ123&x=1")).isEqualTo("GET /cb?access_key=[redacted]&x=1");
    }

    @Test
    void externalIpsAreHashedAndInternalOnesKept() {
        String out = redactor.redact("login from 203.0.113.77 via 10.0.0.5");
        assertThat(out).startsWith("login from ext-ip-").contains("via 10.0.0.5").doesNotContain("203.0.113.77");
        assertThat(redactor.redact("203.0.113.77")).isEqualTo(redactor.redact("203.0.113.77"));
        AiProperties p = new AiProperties();
        p.setInternalCidrs(List.of("10.0.0.0/8", "192.168.0.0/16"));
        assertThat(new Redactor(p).redact("192.168.4.20")).isEqualTo("192.168.4.20");
    }

    @Test
    void ordinaryLogTextIsUntouched() {
        String line = "2026-05-15 10:21:08,889 | WARN | [billing-worker-6] | f552b470-2388-4d62-8828-ee77eba648ed | "
                + "c.a.b.i.PaymentProviderClient:submit(118) | Provider response externalCorrelationId=pp-ee4b0b60bf5d status=504 took 30012 ms";
        assertThat(redactor.redact(line)).isEqualTo(line);
        assertThat(redactor.redact("REQ-20260515-000123 by usr-1042 amount 1200.50 USD")).isEqualTo("REQ-20260515-000123 by usr-1042 amount 1200.50 USD");
        assertThat(redactor.redact(null)).isNull();
    }
}
