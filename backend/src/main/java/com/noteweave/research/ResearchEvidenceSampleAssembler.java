package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.extractListOfMaps;
import static com.noteweave.research.ResearchReadModelMapper.extractStringList;
import static com.noteweave.research.ResearchReadModelMapper.firstNonNull;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds stable source samples for research read-model loop rounds and checkpoint payloads. */
@Component
public class ResearchEvidenceSampleAssembler {

    public void enrichLoopRoundsWithSourceSamples(
            List<Map<String, Object>> loopRounds,
            List<Map<String, Object>> sourceEvidence
    ) {
        if (loopRounds == null || loopRounds.isEmpty() || sourceEvidence == null || sourceEvidence.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> sourceEvidenceById = buildSourceEvidenceById(sourceEvidence);
        for (Map<String, Object> loopRound : loopRounds) {
            List<Map<String, Object>> sourceSamples = buildLoopRoundSourceSamples(
                    loopRound, sourceEvidenceById, sourceEvidence, 2);
            loopRound.put("source_samples", sourceSamples);
            loopRound.put("source_sample_count", sourceSamples.size());
        }
    }

    public void enrichPayloadLoopRoundsWithSourceSamples(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        if (!(payload.get("loop_rounds") instanceof List<?> rawLoopRounds) || rawLoopRounds.isEmpty()) {
            return;
        }
        List<Map<String, Object>> evidenceRecords = buildLoopRoundPayloadEvidenceRecords(payload);
        if (evidenceRecords.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> sourceEvidenceById = buildSourceEvidenceById(evidenceRecords);
        for (Object item : rawLoopRounds) {
            if (!(item instanceof Map<?, ?> rawLoopRound)) {
                continue;
            }
            Map<String, Object> loopRound = castMap(rawLoopRound);
            List<Map<String, Object>> sourceSamples = buildLoopRoundSourceSamples(
                    loopRound, sourceEvidenceById, evidenceRecords, 2);
            @SuppressWarnings("unchecked")
            Map<Object, Object> mutableLoopRound = (Map<Object, Object>) rawLoopRound;
            mutableLoopRound.put("source_samples", sourceSamples);
            mutableLoopRound.put("source_sample_count", sourceSamples.size());
        }
    }

    private Map<String, Map<String, Object>> buildSourceEvidenceById(List<Map<String, Object>> sourceEvidence) {
        if (sourceEvidence == null || sourceEvidence.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Map<String, Object>> evidenceById = new LinkedHashMap<>();
        for (Map<String, Object> evidence : sourceEvidence) {
            String evidenceId = stringValue(evidence.get("evidence_id"));
            if (!evidenceId.isBlank()) {
                evidenceById.put(evidenceId, evidence);
            }
        }
        return evidenceById;
    }

    private List<Map<String, Object>> buildEvidenceSourceSamples(
            List<String> evidenceIds,
            Map<String, Map<String, Object>> sourceEvidenceById,
            int limit
    ) {
        if (evidenceIds == null || evidenceIds.isEmpty() || sourceEvidenceById.isEmpty() || limit <= 0) {
            return List.of();
        }
        LinkedHashMap<String, Map<String, Object>> orderedSamples = new LinkedHashMap<>();
        for (String evidenceId : evidenceIds) {
            String normalizedEvidenceId = blankToNull(evidenceId);
            if (normalizedEvidenceId == null || orderedSamples.size() >= limit) {
                continue;
            }
            Map<String, Object> evidence = sourceEvidenceById.get(normalizedEvidenceId);
            if (evidence == null) {
                continue;
            }
            String sourceKey = defaultIfBlank(
                    stringValue(evidence.get("source_id")),
                    "evidence:" + normalizedEvidenceId
            );
            if (orderedSamples.containsKey(sourceKey)) {
                continue;
            }
            LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
            sample.put("evidence_id", normalizedEvidenceId);
            sample.put("source_id", blankIfNull(stringValue(evidence.get("source_id"))));
            sample.put("source_title", blankIfNull(stringValue(evidence.get("source_title"))));
            sample.put("generated_by", blankIfNull(stringValue(evidence.get("generated_by"))));
            sample.put("generated_ref_id", blankIfNull(stringValue(evidence.get("generated_ref_id"))));
            sample.put("search_query", blankIfNull(stringValue(evidence.get("search_query"))));
            sample.put("read_focus", blankIfNull(stringValue(evidence.get("read_focus"))));
            sample.put("relation_type", blankIfNull(stringValue(evidence.get("relation_type"))));
            orderedSamples.put(sourceKey, sample);
        }
        return new ArrayList<>(orderedSamples.values());
    }

    private List<Map<String, Object>> buildLoopRoundPayloadEvidenceRecords(Map<String, Object> payload) {
        List<Map<String, Object>> evidenceRecords = new ArrayList<>();
        for (Map<String, Object> evidenceCard : extractListOfMaps(payload.get("evidence_cards"))) {
            evidenceRecords.add(normalizeEvidenceRecord(evidenceCard));
        }
        for (Map<String, Object> readWindow : extractListOfMaps(payload.get("read_windows"))) {
            evidenceRecords.add(normalizeEvidenceRecord(readWindow));
        }
        return evidenceRecords;
    }

    private Map<String, Object> normalizeEvidenceRecord(Map<String, Object> source) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("evidence_id", blankIfNull(stringValue(source.get("evidence_id"))));
        normalized.put("source_id", blankIfNull(stringValue(source.get("source_id"))));
        normalized.put("source_title", blankIfNull(stringValue(source.get("source_title"))));
        normalized.put("generated_by", blankIfNull(stringValue(source.get("generated_by"))));
        normalized.put("generated_ref_id", blankIfNull(stringValue(source.get("generated_ref_id"))));
        normalized.put("search_query", blankIfNull(stringValue(firstNonNull(
                source.get("search_query"), source.get("query")
        ))));
        normalized.put("read_focus", blankIfNull(stringValue(source.get("read_focus"))));
        normalized.put("relation_type", blankIfNull(stringValue(source.get("relation_type"))));
        return normalized;
    }

    private List<Map<String, Object>> buildLoopRoundSourceSamples(
            Map<String, Object> loopRound,
            Map<String, Map<String, Object>> sourceEvidenceById,
            List<Map<String, Object>> sourceEvidence,
            int limit
    ) {
        if (loopRound == null || limit <= 0) {
            return List.of();
        }
        List<String> evidenceIds = new ArrayList<>();
        evidenceIds.addAll(extractStringList(loopRound.get("evidence_ids")));
        evidenceIds.addAll(extractStringList(loopRound.get("target_evidence_ids")));
        List<Map<String, Object>> directSamples = buildEvidenceSourceSamples(evidenceIds, sourceEvidenceById, limit);
        if (!directSamples.isEmpty()) {
            return directSamples;
        }

        LinkedHashSet<String> queries = new LinkedHashSet<>(extractStringList(loopRound.get("search_queries")));
        queries.addAll(extractStringList(loopRound.get("queries")));
        String query = blankToNull(stringValue(firstNonNull(loopRound.get("search_query"), loopRound.get("query"))));
        if (query != null) {
            queries.add(query);
        }
        if (queries.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, Map<String, Object>> orderedSamples = new LinkedHashMap<>();
        for (String candidateQuery : queries) {
            for (Map<String, Object> evidence : sourceEvidence) {
                String evidenceQuery = blankToNull(stringValue(evidence.get("search_query")));
                if (evidenceQuery == null || !evidenceQuery.equals(candidateQuery)) {
                    continue;
                }
                String evidenceId = blankToNull(stringValue(evidence.get("evidence_id")));
                String sampleKey = defaultIfBlank(
                        stringValue(evidence.get("source_id")),
                        evidenceId == null ? candidateQuery : evidenceId
                );
                if (orderedSamples.containsKey(sampleKey)) {
                    continue;
                }
                LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
                sample.put("evidence_id", evidenceId);
                sample.put("source_id", blankIfNull(stringValue(evidence.get("source_id"))));
                sample.put("source_title", blankIfNull(stringValue(evidence.get("source_title"))));
                sample.put("generated_by", blankIfNull(stringValue(evidence.get("generated_by"))));
                sample.put("generated_ref_id", blankIfNull(stringValue(evidence.get("generated_ref_id"))));
                sample.put("search_query", evidenceQuery);
                sample.put("read_focus", blankIfNull(stringValue(evidence.get("read_focus"))));
                sample.put("relation_type", blankIfNull(stringValue(evidence.get("relation_type"))));
                orderedSamples.put(sampleKey, sample);
                if (orderedSamples.size() >= limit) {
                    return new ArrayList<>(orderedSamples.values());
                }
            }
        }
        return new ArrayList<>(orderedSamples.values());
    }

    private Map<String, Object> castMap(Map<?, ?> mapValue) {
        LinkedHashMap<String, Object> casted = new LinkedHashMap<>();
        mapValue.forEach((key, value) -> casted.put(String.valueOf(key), value));
        return casted;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
