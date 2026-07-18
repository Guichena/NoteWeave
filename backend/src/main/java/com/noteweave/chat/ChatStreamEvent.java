package com.noteweave.chat;

public record ChatStreamEvent(String id, String event, String data) {
}
