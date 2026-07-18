package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.strategy.EvidenceBudgeter;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.RetrievalPlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QaEvidenceSelectionPolicyTest {

    @Test
    void shouldSelectOnePerSourceBeforeFillingInCandidateOrder() {
        List<Item> candidates = List.of(
                new Item("a-1", "a"),
                new Item("a-2", "a"),
                new Item("b-1", "b"),
                new Item("c-1", "c"),
                new Item("b-2", "b")
        );

        var selected = QaEvidenceSelectionPolicy.select(
                candidates, 4, Item::sourceId, Item::evidenceId);

        assertThat(selected).extracting(item -> item.item().evidenceId())
                .containsExactly("a-1", "b-1", "c-1", "a-2");
        assertThat(selected).extracting(QaEvidenceSelectionPolicy.Selection::sourceDiversity)
                .containsExactly(true, true, true, false);
    }

    @Test
    void shouldEnforceDefaultOnlineLimitAndDeduplicateEvidence() {
        List<Item> candidates = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new Item("item-" + index, "source"))
                .toList();
        List<Item> withDuplicate = new java.util.ArrayList<>(candidates);
        withDuplicate.add(1, candidates.get(0));

        assertThat(QaEvidenceSelectionPolicy.select(
                withDuplicate,
                QaEvidenceSelectionPolicy.DEFAULT_EVIDENCE_LIMIT,
                Item::sourceId,
                Item::evidenceId))
                .extracting(item -> item.item().evidenceId())
                .containsExactly("item-0", "item-1", "item-2", "item-3", "item-4", "item-5");
    }

    @Test
    void shouldMirrorFinalBundleScoreOrderAndCharacterBudget() {
        List<ScoredItem> candidates = List.of(
                new ScoredItem("a-high", "a", 27, 2_000),
                new ScoredItem("a-mid", "a", 15, 2_000),
                new ScoredItem("b-low", "b", 12, 2_000),
                new ScoredItem("c-too-large", "c", 30, 9_000)
        );

        assertThat(QaEvidenceSelectionPolicy.selectFinalBundle(
                candidates,
                ScoredItem::sourceId,
                ScoredItem::evidenceId,
                ScoredItem::score,
                ScoredItem::characters))
                .extracting(ScoredItem::evidenceId)
                .containsExactly("a-high", "a-mid", "b-low");
    }

    @Test
    void shouldStayInParityWithUnifiedEvidenceBudgeter() {
        List<ScoredItem> candidates = List.of(
                new ScoredItem("a-high", "a", 27, 2_000),
                new ScoredItem("a-mid", "a", 15, 2_000),
                new ScoredItem("b-low", "b", 12, 2_000),
                new ScoredItem("c-too-large", "c", 30, 9_000)
        );
        List<ScoredItem> retrieverSelected = QaEvidenceSelectionPolicy.select(
                        candidates,
                        QaEvidenceSelectionPolicy.DEFAULT_EVIDENCE_LIMIT,
                        ScoredItem::sourceId,
                        ScoredItem::evidenceId)
                .stream()
                .map(QaEvidenceSelectionPolicy.Selection::item)
                .toList();
        List<EvidenceBundle.Evidence> onlineSelected = new EvidenceBudgeter().select(
                retrieverSelected.stream().map(this::evidence).toList(),
                new RetrievalPlan.Budget(
                        QaEvidenceSelectionPolicy.DEFAULT_BUNDLE_EVIDENCE_LIMIT,
                        QaEvidenceSelectionPolicy.DEFAULT_BUNDLE_CHARACTER_LIMIT,
                        0,
                        0));

        assertThat(QaEvidenceSelectionPolicy.selectFinalBundle(
                candidates,
                ScoredItem::sourceId,
                ScoredItem::evidenceId,
                ScoredItem::score,
                ScoredItem::characters))
                .extracting(ScoredItem::evidenceId)
                .containsExactlyElementsOf(onlineSelected.stream()
                        .map(EvidenceBundle.Evidence::evidenceId)
                        .toList());
    }

    private EvidenceBundle.Evidence evidence(ScoredItem item) {
        return new EvidenceBundle.Evidence(
                item.evidenceId(), "PASSAGE", item.sourceId(), "snapshot",
                item.evidenceId(), "", "", "title", "content", "chunk:0",
                item.score(), item.score(), item.score(),
                "workspace-source:" + item.sourceId(), Instant.EPOCH,
                "test", item.characters(), Map.of());
    }

    private record Item(String evidenceId, String sourceId) {
    }

    private record ScoredItem(
            String evidenceId,
            String sourceId,
            double score,
            int characters
    ) {
    }
}
