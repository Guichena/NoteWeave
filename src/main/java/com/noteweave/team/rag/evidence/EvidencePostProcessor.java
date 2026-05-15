package com.noteweave.team.rag.evidence;

import com.noteweave.team.rag.retriever.RetrievedChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EvidencePostProcessor {

    public List<EvidenceItem> process(List<RetrievedChunk> chunks, EvidenceOptions options) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> deduplicated = deduplicate(chunks);
        List<RetrievedChunk> sorted = deduplicated.stream()
                .sorted(Comparator.comparing(RetrievedChunk::score, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RetrievedChunk::documentId)
                        .thenComparing(RetrievedChunk::chunkIndex))
                .toList();

        Map<Long, Integer> documentCounts = new LinkedHashMap<>();
        List<EvidenceItem> items = new ArrayList<>();
        int totalChars = 0;
        int citationIndex = 1;
        for (int i = 0; i < sorted.size() && items.size() < options.finalTopK(); i++) {
            RetrievedChunk current = sorted.get(i);
            int count = documentCounts.getOrDefault(current.documentId(), 0);
            if (count >= options.maxEvidencePerDocument()) {
                continue;
            }

            List<RetrievedChunk> mergedGroup = new ArrayList<>();
            mergedGroup.add(current);
            if (options.mergeAdjacentChunks()) {
                while (i + 1 < sorted.size()) {
                    RetrievedChunk next = sorted.get(i + 1);
                    if (!isAdjacent(current, next)) {
                        break;
                    }
                    String candidateContent = mergedContent(mergedGroup, next);
                    if (candidateContent.length() > options.maxMergedChars()) {
                        break;
                    }
                    mergedGroup.add(next);
                    current = next;
                    i++;
                }
            }

            String content = mergedContent(mergedGroup, null);
            if (totalChars + content.length() > options.maxContextChars()) {
                int allowed = Math.max(0, options.maxContextChars() - totalChars);
                if (allowed == 0) {
                    break;
                }
                content = content.substring(0, Math.min(content.length(), allowed)).trim();
            }
            if (content.isBlank()) {
                continue;
            }
            totalChars += content.length();
            RetrievedChunk head = mergedGroup.get(0);
            items.add(new EvidenceItem(
                    citationIndex++,
                    head.documentId(),
                    head.documentTitle(),
                    head.indexVersion(),
                    head.chunkIndex(),
                    content,
                    head.score(),
                    mergedGroup.stream()
                            .map(chunk -> new EvidenceSource(
                                    chunk.chunkId(),
                                    chunk.chunkIndex(),
                                    chunk.pageNo(),
                                    chunk.startOffset(),
                                    chunk.endOffset(),
                                    chunk.content(),
                                    chunk.sourceVersion()
                            ))
                            .toList()
            ));
            documentCounts.put(head.documentId(), count + 1);
            if (totalChars >= options.maxContextChars()) {
                break;
            }
        }
        return items;
    }

    private List<RetrievedChunk> deduplicate(List<RetrievedChunk> chunks) {
        Map<Long, RetrievedChunk> deduplicated = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            deduplicated.putIfAbsent(chunk.chunkId(), chunk);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private boolean isAdjacent(RetrievedChunk left, RetrievedChunk right) {
        return left.documentId().equals(right.documentId())
                && left.indexVersion().equals(right.indexVersion())
                && right.chunkIndex() == left.chunkIndex() + 1;
    }

    private String mergedContent(List<RetrievedChunk> group, RetrievedChunk append) {
        StringBuilder builder = new StringBuilder();
        for (RetrievedChunk chunk : group) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(chunk.content());
        }
        if (append != null) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(append.content());
        }
        return builder.toString().trim();
    }
}
