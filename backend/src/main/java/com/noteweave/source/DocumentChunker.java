package com.noteweave.source;

import com.noteweave.config.NoteWeaveProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunker {

    private final int chunkSize;
    private final int overlap;

    public DocumentChunker(NoteWeaveProperties properties) {
        this.chunkSize = properties.document().chunkSize();
        this.overlap = Math.min(properties.document().chunkOverlap(), Math.max(0, chunkSize / 3));
    }

    public List<String> chunk(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + chunkSize);
            if (end < normalized.length()) {
                int paragraphBreak = normalized.lastIndexOf("\n\n", end);
                if (paragraphBreak > start + chunkSize / 2) {
                    end = paragraphBreak;
                }
            }
            chunks.add(normalized.substring(start, end).trim());
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }
}
