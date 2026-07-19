package com.noteweave.retrieval.provider;

public class RetrievalProviderException extends RuntimeException {
    private final String errorCode;

    public RetrievalProviderException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RetrievalProviderException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
