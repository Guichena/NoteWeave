package com.noteweave.retrieval.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderEndpointSecurityTest {
    @Test
    void permitsHttpsAndLocalDevelopmentHttpOnly() {
        assertThat(ProviderEndpointSecurity.requireSecureOrLocal("https://provider.example/v1/embeddings").getScheme())
                .isEqualTo("https");
        assertThat(ProviderEndpointSecurity.requireSecureOrLocal("http://127.0.0.1:11434/v1/embeddings").getHost())
                .isEqualTo("127.0.0.1");
        assertThat(ProviderEndpointSecurity.requireSecureOrLocal("http://host.docker.internal:11434/v1/rerank").getHost())
                .isEqualTo("host.docker.internal");

        assertThatThrownBy(() -> ProviderEndpointSecurity.requireSecureOrLocal(
                "http://provider.example/v1/embeddings"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }
}
