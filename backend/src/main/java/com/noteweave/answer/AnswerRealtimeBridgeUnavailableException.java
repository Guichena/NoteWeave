package com.noteweave.answer;

/**
 * Signals that the optional cross-instance realtime transport is unavailable.
 * Callers may continue with the in-process mux, but must not treat this as an
 * empty remote event batch.
 */
public class AnswerRealtimeBridgeUnavailableException extends RuntimeException {

    public AnswerRealtimeBridgeUnavailableException(String operation, Throwable cause) {
        super("Answer realtime bridge unavailable during " + operation, cause);
    }
}
