package com.noteweave.chat;

import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EvidenceCitationAssembler {

    private final JdbcTemplate jdbcTemplate;

    public EvidenceCitationAssembler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int persist(String workspaceId, String messageId, EvidenceBundle bundle, PromptSpec prompt) {
        List<AssembledCitation> citations = assemble(bundle, prompt);
        for (int index = 0; index < citations.size(); index++) {
            AssembledCitation citation = citations.get(index);
            String citationId = Ids.newId();
            jdbcTemplate.update("""
                    insert into citation(id, workspace_id, source_id, source_snapshot_id, source_chunk_id, title, quote_text, page_no, location_info)
                    values (?, ?, ?, ?, ?, ?, ?, (select sc.page_start from source_chunk sc where sc.id = ?), ?)
                    """, citationId, workspaceId, citation.sourceId(), citation.sourceSnapshotId(),
                    citation.passageId(), citation.title(), trim(citation.quoteText(), 360), citation.passageId(),
                    citation.location());
            jdbcTemplate.update("""
                    insert into message_citation(id, message_id, citation_id, sort_order)
                    values (?, ?, ?, ?)
                    """, Ids.newId(), messageId, citationId, index);
        }
        return citations.size();
    }

    List<AssembledCitation> assemble(EvidenceBundle bundle, PromptSpec prompt) {
        if (bundle == null || prompt == null) {
            throw new BusinessException(
                    "EVIDENCE_CITATION_INPUT_INVALID",
                    "EvidenceBundle and PromptSpec are both required for citation assembly"
            );
        }
        Map<String, EvidenceBundle.Evidence> evidenceById = new LinkedHashMap<>();
        for (EvidenceBundle.Evidence evidence : bundle.evidence()) {
            EvidenceBundle.Evidence previous = evidenceById.putIfAbsent(evidence.evidenceId(), evidence);
            if (previous != null) {
                throw new BusinessException(
                        "EVIDENCE_ID_DUPLICATED",
                        "Duplicate evidence id in bundle: " + evidence.evidenceId()
                );
            }
        }
        List<AssembledCitation> result = new ArrayList<>();
        Set<String> referencedIds = new LinkedHashSet<>();
        for (String evidenceId : prompt.referencedEvidenceIds()) {
            if (!referencedIds.add(evidenceId)) {
                throw new BusinessException(
                        "PROMPT_EVIDENCE_DUPLICATED",
                        "Prompt references the same evidence more than once: " + evidenceId
                );
            }
            EvidenceBundle.Evidence evidence = evidenceById.get(evidenceId);
            if (evidence == null) {
                throw new BusinessException(
                        "PROMPT_EVIDENCE_NOT_FOUND",
                        "Prompt references evidence outside bundle: " + evidenceId
                );
            }
            if ("KNOWLEDGE_VERSION".equals(evidence.kind())) {
                if (isBlank(evidence.knowledgeItemId()) || isBlank(evidence.knowledgeVersionId())) {
                    throw new BusinessException(
                            "EVIDENCE_CITATION_UNSUPPORTED",
                            "Knowledge evidence requires item and version ids: " + evidenceId
                    );
                }
                continue;
            }
            if (!"PASSAGE".equals(evidence.kind())
                    || isBlank(evidence.sourceId())
                    || isBlank(evidence.sourceSnapshotId())
                    || isBlank(evidence.passageId())) {
                throw new BusinessException(
                        "EVIDENCE_CITATION_UNSUPPORTED",
                        "Citation requires passage evidence with source, snapshot and passage ids: " + evidenceId
                );
            }
            result.add(new AssembledCitation(
                    evidenceId,
                    evidence.sourceId(),
                    evidence.sourceSnapshotId(),
                    evidence.passageId(),
                    evidence.metadata().getOrDefault("raw_title", evidence.title()),
                    evidence.excerpt(),
                    evidence.location()
            ));
        }
        return List.copyOf(result);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max - 1) + "...";
    }

    record AssembledCitation(
            String evidenceId,
            String sourceId,
            String sourceSnapshotId,
            String passageId,
            String title,
            String quoteText,
            String location
    ) {
    }
}
