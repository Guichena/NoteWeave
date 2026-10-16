package com.noteweave.research;

import com.google.common.net.InternetDomainName;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 按注册域名、文件血缘及引文 SimHash 合并来源，再计算独立来源置信度。 */
final class ResearchSourceIndependence {
    static final int CONFIDENCE_CAP = 950_000;
    private static final Pattern TOKENS = Pattern.compile("[a-z0-9]+|[\u3400-\u9fff]");
    private ResearchSourceIndependence() { }

    static String registeredDomain(String domain) {
        if (domain == null) return "";
        String normalized = Normalizer.normalize(domain, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("workspace-source:") || normalized.startsWith("external-source:")) return normalized;
        try {
            InternetDomainName name = InternetDomainName.from(normalized);
            return name.isUnderPublicSuffix() ? name.topPrivateDomain().toString() : name.toString();
        } catch (IllegalArgumentException exception) {
            return normalized;
        }
    }

    static List<String> tokens(String quote) {
        String normalized = Normalizer.normalize(quote, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        var matcher = TOKENS.matcher(normalized);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    static long simhash(String quote) {
        Map<String, Integer> counts = new HashMap<>();
        tokens(quote).forEach(token -> counts.merge(token, 1, Integer::sum));
        int[] weights = new int[64];
        counts.forEach((token, count) -> {
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
                long hash = java.nio.ByteBuffer.wrap(bytes).getLong();
                for (int bit = 0; bit < 64; bit++) weights[bit] += (hash & (1L << bit)) != 0 ? count : -count;
            } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        });
        long hash = 0;
        for (int bit = 0; bit < 64; bit++) if (weights[bit] > 0) hash |= 1L << bit;
        return hash;
    }

    static boolean overlap(Source left, Source right) {
        return registeredDomain(left.domain()).equals(registeredDomain(right.domain()))
                || (!left.lineage().isBlank() && left.lineage().equals(right.lineage()))
                || left.quote().equals(right.quote())
                || (Math.min(tokens(left.quote()).size(), tokens(right.quote()).size()) >= 4
                    && Long.bitCount(simhash(left.quote()) ^ simhash(right.quote())) <= 3);
    }

    static String fingerprint(String quote) {
        if (tokens(quote).size() >= 4) return "simhash:" + String.format("%016x", simhash(quote));
        try {
            return "exact:" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(quote.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static boolean overlappingFingerprints(List<String> left, List<String> right) {
        for (String a : left) for (String b : right) {
            if (a.equals(b)) return true;
            if (a.startsWith("simhash:") && b.startsWith("simhash:")
                    && Long.bitCount(Long.parseUnsignedLong(a.substring(8), 16) ^ Long.parseUnsignedLong(b.substring(8), 16)) <= 3) return true;
        }
        return false;
    }

    static Score score(List<Source> sources) {
        int[] parents = new int[sources.size()];
        for (int i = 0; i < parents.length; i++) parents[i] = i;
        for (int i = 0; i < sources.size(); i++) for (int j = 0; j < i; j++) {
            if (overlap(sources.get(i), sources.get(j))) parents[root(parents, i)] = root(parents, j);
        }
        Map<Integer, Integer> strengths = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) strengths.merge(root(parents, i), sources.get(i).strength(), Math::max);
        BigDecimal remaining = BigDecimal.ONE;
        for (int strength : strengths.values()) remaining = remaining.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(strength, 6)));
        int confidence = BigDecimal.ONE.subtract(remaining).movePointRight(6).setScale(0, RoundingMode.HALF_UP).intValueExact();
        return new Score(strengths.size(), Math.min(CONFIDENCE_CAP, confidence));
    }

    private static int root(int[] parents, int i) {
        while (parents[i] != i) { parents[i] = parents[parents[i]]; i = parents[i]; }
        return i;
    }

    record Source(String domain, String lineage, String quote, int strength) {
        Source {
            domain = domain == null ? "" : domain;
            lineage = lineage == null ? "" : lineage;
            quote = quote == null ? "" : quote;
        }
    }
    record Score(int independentSources, int confidencePpm) { }
}
