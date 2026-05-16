package com.noteweave.personal.source.fetch;

public interface UrlContentFetcher {

    void validate(String url);

    FetchedUrlContent fetch(String url);
}
