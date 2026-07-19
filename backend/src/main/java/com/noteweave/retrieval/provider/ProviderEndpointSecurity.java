package com.noteweave.retrieval.provider;

import java.net.URI;
import java.util.Locale;

final class ProviderEndpointSecurity {
    private ProviderEndpointSecurity() { }

    static URI requireSecureOrLocal(String value) {
        URI endpoint = URI.create(value);
        if ("https".equalsIgnoreCase(endpoint.getScheme()) || isLocalHttp(endpoint)) {
            return endpoint;
        }
        throw new IllegalArgumentException(
                "Retrieval provider endpoint must use HTTPS unless it is a local development endpoint");
    }

    private static boolean isLocalHttp(URI endpoint) {
        if (!"http".equalsIgnoreCase(endpoint.getScheme())) return false;
        String host = endpoint.getHost();
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1")
                || normalized.startsWith("127.")
                || normalized.equals("host.docker.internal");
    }
}
