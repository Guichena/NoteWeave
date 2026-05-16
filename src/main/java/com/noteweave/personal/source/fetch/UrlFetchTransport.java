package com.noteweave.personal.source.fetch;

import java.net.URI;
import java.time.Duration;

public interface UrlFetchTransport {

    UrlFetchTransportResponse fetch(URI uri, Duration timeout);
}
