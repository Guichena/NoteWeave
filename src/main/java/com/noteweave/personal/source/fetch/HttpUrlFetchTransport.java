package com.noteweave.personal.source.fetch;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpUrlFetchTransport implements UrlFetchTransport {

    private final HttpClient httpClient;

    public HttpUrlFetchTransport(
            @Value("${noteweave.personal.source.url-fetch.connect-timeout-seconds:5}") int connectTimeoutSeconds
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(connectTimeoutSeconds, 1)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public UrlFetchTransportResponse fetch(URI uri, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("User-Agent", "NoteWeave/1.0")
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new UrlFetchTransportResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Failed to fetch url source: " + ex.getMessage());
        }
    }
}
