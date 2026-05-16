package com.noteweave.personal.source.fetch;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SafeUrlContentFetcher implements UrlContentFetcher {

    private static final String UNSAFE_MESSAGE = "URL points to an unsafe address";

    private final UrlFetchTransport transport;
    private final HostAddressResolver addressResolver;
    private final Duration readTimeout;
    private final int maxRedirects;
    private final int maxResponseBytes;

    @Autowired
    public SafeUrlContentFetcher(
            UrlFetchTransport transport,
            @Value("${noteweave.personal.source.url-fetch.read-timeout-seconds:10}") int readTimeoutSeconds,
            @Value("${noteweave.personal.source.url-fetch.max-redirects:3}") int maxRedirects,
            @Value("${noteweave.personal.source.url-fetch.max-response-bytes:1048576}") int maxResponseBytes
    ) {
        this(transport, HostAddressResolver.system(), Duration.ofSeconds(Math.max(readTimeoutSeconds, 1)), maxRedirects, maxResponseBytes);
    }

    SafeUrlContentFetcher(
            UrlFetchTransport transport,
            HostAddressResolver addressResolver,
            int maxRedirects,
            int maxResponseBytes
    ) {
        this(transport, addressResolver, Duration.ofSeconds(10), maxRedirects, maxResponseBytes);
    }

    SafeUrlContentFetcher(
            UrlFetchTransport transport,
            HostAddressResolver addressResolver,
            Duration readTimeout,
            int maxRedirects,
            int maxResponseBytes
    ) {
        this.transport = transport;
        this.addressResolver = addressResolver;
        this.readTimeout = readTimeout;
        this.maxRedirects = Math.max(maxRedirects, 0);
        this.maxResponseBytes = Math.max(maxResponseBytes, 1);
    }

    @Override
    public void validate(String url) {
        URI uri = parse(url, ErrorCode.VALIDATION_FAILED, "url: invalid url");
        validateUri(uri, ErrorCode.VALIDATION_FAILED, "url: unsafe url");
    }

    @Override
    public FetchedUrlContent fetch(String url) {
        URI current = parse(url, ErrorCode.SOURCE_IMPORT_FAILED, "URL source has an invalid url");
        validateUri(current, ErrorCode.SOURCE_IMPORT_FAILED, UNSAFE_MESSAGE);

        for (int redirectCount = 0; ; redirectCount++) {
            UrlFetchTransportResponse response = transport.fetch(current, readTimeout);
            if (isRedirect(response.statusCode())) {
                if (redirectCount >= maxRedirects) {
                    throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "URL source exceeded redirect limit");
                }
                String location = response.firstHeader("location");
                if (location == null || location.isBlank()) {
                    throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Redirect response is missing Location header");
                }
                current = current.resolve(location).normalize();
                validateUri(current, ErrorCode.SOURCE_IMPORT_FAILED, UNSAFE_MESSAGE);
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "URL source returned status " + response.statusCode());
            }
            return new FetchedUrlContent(readBody(response.body()), response.firstHeader("content-type"));
        }
    }

    private URI parse(String url, ErrorCode errorCode, String message) {
        try {
            return URI.create(url).normalize();
        } catch (Exception ex) {
            throw new BusinessException(errorCode, message);
        }
    }

    private void validateUri(URI uri, ErrorCode errorCode, String unsafeMessage) {
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(errorCode, "Only http/https URLs are allowed");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new BusinessException(errorCode, "URL user info is not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(errorCode, "URL host is required");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalizedHost) || normalizedHost.endsWith(".localhost") || normalizedHost.endsWith(".local")) {
            throw new BusinessException(errorCode, unsafeMessage);
        }
        List<InetAddress> addresses;
        try {
            addresses = addressResolver.resolve(host);
        } catch (Exception ex) {
            throw new BusinessException(errorCode, "Failed to resolve URL host: " + ex.getMessage());
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new BusinessException(errorCode, "Failed to resolve URL host");
        }
        for (InetAddress address : addresses) {
            if (address == null || isBlocked(address)) {
                throw new BusinessException(errorCode, unsafeMessage);
            }
        }
    }

    private byte[] readBody(InputStream inputStream) {
        if (inputStream == null) {
            return new byte[0];
        }
        try (InputStream bodyStream = inputStream; ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = bodyStream.read(buffer)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "URL source response is too large");
                }
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Failed to read url source body: " + ex.getMessage());
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if (first == 0 || first == 10 || first == 127) {
                return true;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            if (first == 169 && second == 254) {
                return true;
            }
            if (first == 172 && second >= 16 && second <= 31) {
                return true;
            }
            if (first == 192 && second == 168) {
                return true;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return true;
            }
            return first >= 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if ((first & 0xfe) == 0xfc) {
                return true;
            }
            return first == 0xfe && (second & 0xc0) == 0x80;
        }
        return false;
    }
}
