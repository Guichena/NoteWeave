package com.noteweave.worker;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class ArtifactWorkerRestClientFactory {

    private ArtifactWorkerRestClientFactory() {
    }

    public static RestClient create(
            String baseUrl,
            String internalAuthToken,
            long connectTimeoutSeconds,
            long readTimeoutSeconds
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1, readTimeoutSeconds)));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
        if (internalAuthToken != null && !internalAuthToken.isBlank()) {
            builder.defaultHeader("X-NoteWeave-Internal-Token", internalAuthToken.trim());
        }
        return builder.build();
    }
}
