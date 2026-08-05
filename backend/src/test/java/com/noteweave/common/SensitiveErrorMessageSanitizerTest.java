package com.noteweave.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveErrorMessageSanitizerTest {

    @Test
    void sanitizesAuthorizationSchemesAndCookieHeaders() {
        String sanitized = SensitiveErrorMessageSanitizer.sanitize("""
                Authorization: Basic dXNlcjpwYXNz
                Proxy-Authorization: Digest private-response
                Cookie: session=private-cookie; Path=/
                Set-Cookie: refresh=private-refresh; HttpOnly
                """);

        assertThat(sanitized)
                .doesNotContain("dXNlcjpwYXNz", "private-response", "private-cookie", "private-refresh")
                .contains("Authorization: Basic [REDACTED]")
                .contains("Proxy-Authorization: Digest [REDACTED]")
                .contains("Cookie: [REDACTED]")
                .contains("Set-Cookie: [REDACTED]");
    }
}
