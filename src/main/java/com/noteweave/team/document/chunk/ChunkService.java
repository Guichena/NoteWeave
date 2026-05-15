package com.noteweave.team.document.chunk;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChunkService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final int chunkSize;
    private final int chunkOverlap;

    public ChunkService(
            @Value("${noteweave.document.parsing.chunk-size:800}") int chunkSize,
            @Value("${noteweave.document.parsing.chunk-overlap:120}") int chunkOverlap
    ) {
        this.chunkSize = Math.max(chunkSize, 40);
        this.chunkOverlap = Math.max(Math.min(chunkOverlap, this.chunkSize / 2), 0);
    }

    public List<ChunkCandidate> split(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY_TEXT, "parsed text is empty");
        }

        List<ChunkCandidate> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < normalized.length()) {
            int end = chooseEnd(normalized, start);
            String content = normalized.substring(start, end).trim();
            if (!content.isBlank()) {
                chunks.add(new ChunkCandidate(
                        index++,
                        content,
                        sha256(content),
                        estimateTokenCount(content),
                        start,
                        end,
                        null,
                        detectSectionTitle(content)
                ));
            }
            if (end >= normalized.length()) {
                break;
            }
            int nextStart = Math.max(end - chunkOverlap, start + 1);
            start = skipLeadingWhitespace(normalized, nextStart);
        }
        return chunks;
    }

    private int chooseEnd(String text, int start) {
        int hardEnd = Math.min(start + chunkSize, text.length());
        if (hardEnd == text.length()) {
            return hardEnd;
        }
        int paragraphBreak = text.lastIndexOf("\n\n", hardEnd);
        if (paragraphBreak > start + chunkSize / 2) {
            return paragraphBreak;
        }
        int sentenceBreak = Math.max(text.lastIndexOf(". ", hardEnd), text.lastIndexOf("。", hardEnd));
        if (sentenceBreak > start + chunkSize / 2) {
            return Math.min(sentenceBreak + 1, text.length());
        }
        int whitespace = text.lastIndexOf(" ", hardEnd);
        if (whitespace > start + chunkSize / 2) {
            return whitespace;
        }
        return hardEnd;
    }

    private int skipLeadingWhitespace(String text, int start) {
        int position = start;
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
        return position;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalizedLineBreaks = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalizedLineBreaks.stripLeading().startsWith("#")) {
            return WHITESPACE.matcher(normalizedLineBreaks).replaceAll(" ").trim();
        }
        StringBuilder builder = new StringBuilder(normalizedLineBreaks.length());
        boolean previousWasSpace = false;
        int consecutiveNewlines = 0;
        for (int i = 0; i < normalizedLineBreaks.length(); i++) {
            char value = normalizedLineBreaks.charAt(i);
            if (value == '\n') {
                consecutiveNewlines++;
                previousWasSpace = false;
                if (consecutiveNewlines <= 2) {
                    builder.append('\n');
                }
                continue;
            }
            consecutiveNewlines = 0;
            if (Character.isWhitespace(value)) {
                if (!previousWasSpace) {
                    builder.append(' ');
                    previousWasSpace = true;
                }
                continue;
            }
            builder.append(value);
            previousWasSpace = false;
        }
        return builder.toString().trim();
    }

    private int estimateTokenCount(String content) {
        return Math.max(1, (int) Math.ceil(content.length() / 4.0));
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_CHUNK_FAILED, "failed to hash chunk");
        }
    }

    private String detectSectionTitle(String content) {
        if (content.startsWith("#")) {
            int lineEnd = content.indexOf('\n');
            String firstLine = lineEnd >= 0 ? content.substring(0, lineEnd) : content;
            return firstLine.replaceFirst("^#+\\s*", "").trim();
        }
        return null;
    }
}
