package com.noteweave.team.wiki.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class WikiLinkParser {

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\[\\]\\r\\n]+)\\]\\]");

    public Map<String, Integer> extractLinkTargets(String content) {
        Map<String, Integer> targets = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return targets;
        }
        Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String targetTitle = normalizeLinkTarget(matcher.group(1));
            if (targetTitle == null) {
                continue;
            }
            targets.merge(targetTitle, 1, Integer::sum);
        }
        return targets;
    }

    public String normalizeLookupKey(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeLinkTarget(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        int pipeIndex = normalized.indexOf('|');
        if (pipeIndex >= 0) {
            normalized = normalized.substring(0, pipeIndex).trim();
        }
        int headingAnchorIndex = normalized.indexOf('#');
        if (headingAnchorIndex >= 0) {
            normalized = normalized.substring(0, headingAnchorIndex).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }
}
