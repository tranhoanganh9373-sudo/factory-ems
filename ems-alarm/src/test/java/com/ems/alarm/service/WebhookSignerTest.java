package com.ems.alarm.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    @Test
    void hmacSha256_knownVector() {
        // HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")
        // = f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8
        String sig = WebhookSigner.sign("key",
                "The quick brown fox jumps over the lazy dog");
        assertThat(sig).isEqualTo(
                "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void emptySecret_returnsEmptySignature() {
        assertThat(WebhookSigner.sign(null, "data")).isEmpty();
        assertThat(WebhookSigner.sign("",   "data")).isEmpty();
    }

    @Test
    void differentBody_producesDifferentSignature() {
        String s1 = WebhookSigner.sign("k", "a");
        String s2 = WebhookSigner.sign("k", "b");
        assertThat(s1).isNotEqualTo(s2);
        assertThat(s1).startsWith("sha256=");
    }
}
