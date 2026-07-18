package com.noteweave.infra;

public record TaskOutboxMetricsResponse(int ready, int processing, int sent, int deadLetter) {
}
