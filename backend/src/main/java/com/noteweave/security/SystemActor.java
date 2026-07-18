package com.noteweave.security;

import java.util.Locale;

public final class SystemActor {

    private SystemActor() {
    }

    public static String of(String component) {
        String normalized = component == null ? "BACKEND" : component.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid system actor component");
        }
        return "SYSTEM:" + normalized;
    }
}
