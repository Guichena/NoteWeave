package com.noteweave.common;

import java.util.regex.Pattern;

/** Keeps provider and worker failures actionable without persisting credentials or host paths. */
public final class SensitiveErrorMessageSanitizer {

    private static final int MAX_LENGTH = 1000;
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)((?:proxy-)?authorization\\s*[:=]\\s*(?:[A-Za-z][A-Za-z0-9+._-]*\\s+)?)[^\\s,;]+"
    );
    private static final Pattern COOKIE = Pattern.compile(
            "(?im)((?:set-cookie|cookie)\\s*[:=]\\s*)[^\\r\\n]+"
    );
    private static final Pattern SECRET_FIELDS = Pattern.compile(
            "(?i)((?:api[-_]?key|token|password|secret)\\s*[:=]\\s*)[^\\s,;]+"
    );
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?:(?:[A-Za-z]:\\\\)|/)(?:[^\\s,;\\\"]+[/\\\\])*[^\\s,;\\\"]*"
    );

    private SensitiveErrorMessageSanitizer() {
    }

    public static String sanitize(String message) {
        String value = message == null ? "" : message;
        value = AUTHORIZATION.matcher(value).replaceAll("$1[REDACTED]");
        value = COOKIE.matcher(value).replaceAll("$1[REDACTED]");
        value = SECRET_FIELDS.matcher(value).replaceAll("$1[REDACTED]");
        value = ABSOLUTE_PATH.matcher(value).replaceAll("[PATH_REDACTED]");
        return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH);
    }
}
