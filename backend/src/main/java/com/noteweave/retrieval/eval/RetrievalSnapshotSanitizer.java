package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.RetrievalGoldSet.Candidate;
import com.noteweave.retrieval.eval.RetrievalGoldSet.GoldCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class RetrievalSnapshotSanitizer {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s]+", Pattern.UNICODE_CASE);
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<!\\d)(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)\\b[A-Z]:[\\\\/](?:[^\\s\\\\/]+[\\\\/])*[^\\s)\\]}>`'\",;]*");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?token|token|secret)\\s*[:=]\\s*[A-Za-z0-9._-]{8,}");
    private static final String SALT_ENV = "NOTEWEAVE_RETRIEVAL_EXPORT_SALT";

    private final ObjectMapper objectMapper;

    public RetrievalSnapshotSanitizer() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    RetrievalSnapshotSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RedactionSession openSession(String salt) {
        requireSalt(salt);
        return new RedactionSession(salt);
    }

    public SanitizedExport sanitize(RetrievalGoldSet raw, String salt) {
        requireSalt(salt);
        MutableRedactionStats stats = new MutableRedactionStats();
        List<GoldCase> sanitizedCases = raw.cases().stream()
                .map(goldCase -> sanitizeCase(goldCase, salt, stats))
                .toList();
        RetrievalGoldSet sanitized = new RetrievalGoldSet(
                raw.schemaVersion(),
                raw.datasetVersion() + "-sanitized",
                sanitizedCases
        );
        return new SanitizedExport(sanitized, stats.freeze());
    }

    public SanitizedExport export(Path input, Path output, String salt) throws Exception {
        RetrievalGoldSet raw = objectMapper.readValue(input.toFile(), RetrievalGoldSet.class);
        SanitizedExport result = sanitize(raw, salt);
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result.goldSet());
        return result;
    }

    public String pseudonym(String kind, String value, String salt) {
        if (value == null || value.isBlank()) {
            return "";
        }
        requireSalt(salt);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((kind + "\u0000" + value).getBytes(StandardCharsets.UTF_8));
            return kind + "-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to pseudonymize retrieval snapshot id", ex);
        }
    }

    private GoldCase sanitizeCase(GoldCase goldCase, String salt, MutableRedactionStats stats) {
        return new GoldCase(
                pseudonym("case", goldCase.id(), salt),
                goldCase.mode(),
                pseudonym("workspace", goldCase.workspaceId(), salt),
                redact(goldCase.query(), stats),
                pseudonyms("source", goldCase.allowedSourceIds(), salt),
                goldCase.candidates().stream()
                        .map(candidate -> sanitizeCandidate(candidate, salt, stats))
                        .toList(),
                pseudonyms("evidence", goldCase.relevantEvidenceIds(), salt),
                pseudonyms("citation", goldCase.expectedCitationIds(), salt),
                goldCase.shouldRefuse(),
                goldCase.topK()
        );
    }

    private Candidate sanitizeCandidate(Candidate candidate, String salt, MutableRedactionStats stats) {
        return new Candidate(
                pseudonym("evidence", candidate.evidenceId(), salt),
                pseudonym("source", candidate.sourceId(), salt),
                redact(candidate.title(), stats),
                redact(candidate.content(), stats),
                redact(candidate.sourceType(), stats),
                pseudonyms("citation", candidate.citationIds(), salt)
        );
    }

    private List<String> pseudonyms(String kind, List<String> values, String salt) {
        return values.stream().map(value -> pseudonym(kind, value, salt)).toList();
    }

    private String redact(String value, MutableRedactionStats stats) {
        String redacted = value == null ? "" : value;
        redacted = replace(redacted, SECRET, "$1=[REDACTED]", count -> stats.secretCount += count);
        redacted = replace(redacted, EMAIL, "[EMAIL]", count -> stats.emailCount += count);
        redacted = replace(redacted, URL, "[URL]", count -> stats.urlCount += count);
        redacted = replace(redacted, UUID, "[UUID]", count -> stats.uuidCount += count);
        redacted = replace(redacted, IPV4, "[IP]", count -> stats.ipCount += count);
        redacted = replace(redacted, PHONE, "[PHONE]", count -> stats.phoneCount += count);
        return replace(redacted, WINDOWS_PATH, "[PATH]", count -> stats.pathCount += count);
    }

    private String replace(
            String value,
            Pattern pattern,
            String replacement,
            java.util.function.IntConsumer counter
    ) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer output = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            count++;
            matcher.appendReplacement(output, replacement);
        }
        matcher.appendTail(output);
        if (count > 0) {
            counter.accept(count);
        }
        return output.toString();
    }

    private void requireSalt(String salt) {
        if (salt == null || salt.length() < 16) {
            throw new IllegalArgumentException("Retrieval export salt must contain at least 16 characters");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: RetrievalSnapshotSanitizer <raw-gold.json> <sanitized-gold.json>; salt from "
                            + SALT_ENV);
        }
        String salt = System.getenv(SALT_ENV);
        RetrievalSnapshotSanitizer sanitizer = new RetrievalSnapshotSanitizer();
        SanitizedExport result = sanitizer.export(Path.of(args[0]), Path.of(args[1]), salt);
        System.out.println(sanitizer.objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(result.redactionStats()));
    }

    public record SanitizedExport(
            RetrievalGoldSet goldSet,
            RedactionStats redactionStats
    ) {
    }

    public record RedactionStats(
            int emailCount,
            int urlCount,
            int uuidCount,
            int ipCount,
            int phoneCount,
            int pathCount,
            int secretCount
    ) {
        public int totalCount() {
            return emailCount + urlCount + uuidCount + ipCount + phoneCount + pathCount + secretCount;
        }
    }

    public final class RedactionSession {
        private final String salt;
        private final MutableRedactionStats stats = new MutableRedactionStats();

        private RedactionSession(String salt) {
            this.salt = salt;
        }

        public String pseudonym(String kind, String value) {
            return RetrievalSnapshotSanitizer.this.pseudonym(kind, value, salt);
        }

        public String redact(String value) {
            return RetrievalSnapshotSanitizer.this.redact(value, stats);
        }

        public RedactionStats stats() {
            return stats.freeze();
        }
    }

    private static final class MutableRedactionStats {
        private int emailCount;
        private int urlCount;
        private int uuidCount;
        private int ipCount;
        private int phoneCount;
        private int pathCount;
        private int secretCount;

        private RedactionStats freeze() {
            return new RedactionStats(
                    emailCount,
                    urlCount,
                    uuidCount,
                    ipCount,
                    phoneCount,
                    pathCount,
                    secretCount
            );
        }
    }
}
