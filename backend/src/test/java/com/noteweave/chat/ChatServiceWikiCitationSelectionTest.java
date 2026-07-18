package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.strategy.EvidenceBundle;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatServiceWikiCitationSelectionTest {

    @Test
    void shouldPreserveLegacyCitationOrderWhenAllWikiEvidenceIsSelected() {
        EvidenceBundle bundle = bundle(
                List.of(evidence("version-a", "citation-a"), evidence("version-b", "citation-b")),
                Map.of(
                        WikiEvidenceRetriever.RETRIEVED_EVIDENCE_IDS_METADATA,
                        "knowledge-version:version-a,knowledge-version:version-b",
                        WikiEvidenceRetriever.EXISTING_CITATION_IDS_METADATA,
                        "citation-b,citation-a,citation-b"
                )
        );

        assertThat(ChatService.selectedWikiCitationIds(bundle))
                .containsExactly("citation-b", "citation-a", "citation-b");
    }

    @Test
    void shouldKeepOnlyCitationsOwnedByBudgetSelectedWikiEvidence() {
        EvidenceBundle bundle = bundle(
                List.of(evidence("version-a", "citation-a,citation-shared")),
                Map.of(
                        WikiEvidenceRetriever.RETRIEVED_EVIDENCE_IDS_METADATA,
                        "knowledge-version:version-a,knowledge-version:version-b",
                        WikiEvidenceRetriever.EXISTING_CITATION_IDS_METADATA,
                        "citation-a,citation-b,citation-shared"
                )
        );

        assertThat(ChatService.selectedWikiCitationIds(bundle))
                .containsExactly("citation-a", "citation-shared");
    }

    private EvidenceBundle bundle(
            List<EvidenceBundle.Evidence> evidence,
            Map<String, String> metadata
    ) {
        return new EvidenceBundle(
                "bundle", WikiAnswerModeStrategy.PLAN_VERSION, evidence,
                false, List.of(), Instant.now(), metadata);
    }

    private EvidenceBundle.Evidence evidence(String versionId, String citationIds) {
        return new EvidenceBundle.Evidence(
                "knowledge-version:" + versionId,
                "KNOWLEDGE_VERSION",
                "",
                "",
                "",
                "item-" + versionId,
                versionId,
                "Page " + versionId,
                "summary",
                "knowledge-version:" + versionId,
                1,
                1,
                1,
                "workspace-knowledge:item-" + versionId,
                null,
                "wiki-page-graph",
                7,
                Map.of("citation_ids", citationIds)
        );
    }
}
