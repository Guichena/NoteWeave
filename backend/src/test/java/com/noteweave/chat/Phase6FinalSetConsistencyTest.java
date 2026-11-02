package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.EvidenceBudgeter;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.NoteRecallPlan;
import com.noteweave.chat.NoteRetrievalService.NoteRecallTrace;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiPageContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * F6-05：三种模式的最终证据集合在正文、顺序、绑定与成本上使用同一份冻结结果。
 * QA/Note/Wiki 各自负责召回与选择，公共层不得再为某个模式另造一份正文或另一套编号。
 */
class Phase6FinalSetConsistencyTest {

    private static final String EXCERPT = "统一证据正文" + "内容".repeat(700) + "末尾标记";

    @Test
    void allThreeModesRenderTheSameFrozenExcerptAndBindingOrder() {
        EvidenceBundle bundle = bundle(List.of(evidence("e-1", "Doc", EXCERPT)),
                AnswerMode.QA.name());
        AnswerContext context = context();

        var qa = new QaAnswerModeStrategy().compose(context, bundle);
        var note = noteStrategy().compose(context, noteBundle(bundle));
        var wiki = wikiStrategy().compose(context, wikiBundle(bundle));

        for (var prompt : List.of(qa, note, wiki)) {
            // 正文、末尾标记、编号绑定都来自同一个冻结证据，不在展示层二次裁剪
            assertThat(prompt.userPrompt()).contains(EXCERPT);
            assertThat(prompt.userPrompt()).doesNotContain("...");
            // 各模式保留自己的证据身份，但编号顺序与冻结集合一致
            assertThat(prompt.referencedEvidenceIds()).hasSize(1);
        }
        assertThat(qa.referencedEvidenceIds()).containsExactly("e-1");
        assertThat(note.referencedEvidenceIds()).containsExactly("note-window:chunk:0");
        assertThat(wiki.referencedEvidenceIds()).containsExactly("knowledge-version:version");
    }

    @Test
    void evidenceBudgeterPreservesFinalOrderAndSkipsInsteadOfReordering() {
        EvidenceBundle.Evidence first = evidence("e-1", "First", "12345", 10);
        EvidenceBundle.Evidence oversized = evidence("e-2", "Second", "x".repeat(100), 100);
        EvidenceBundle.Evidence third = evidence("e-3", "Third", "67890", 10);

        List<EvidenceBundle.Evidence> selected = new EvidenceBudgeter().select(
                List.of(first, oversized, third),
                new RetrievalPlan.Budget(2, 20, 0, 0));

        // 不同量纲不再全局重排：跳过放不下的证据，保留原最终顺序
        assertThat(selected).extracting(EvidenceBundle.Evidence::evidenceId)
                .containsExactly("e-1", "e-3");
    }

    @Test
    void eachModeKeepsItsOwnRetrievalPlanBoundary() {
        AnswerContext context = context();

        assertThat(new QaAnswerModeStrategy().plan(context).mode()).isEqualTo(AnswerMode.QA);
        assertThat(new NoteAnswerModeStrategy(noteCodec()).plan(context).mode()).isEqualTo(AnswerMode.NOTE);
        assertThat(new WikiAnswerModeStrategy(wikiCodec()).plan(context).mode()).isEqualTo(AnswerMode.WIKI);
        assertThat(new WikiAnswerModeStrategy(wikiCodec()).plan(context).budget().maxEvidence())
                .isEqualTo(WikiEvidenceRetriever.SEED_CANDIDATE_LIMIT + 30);
    }

    private AnswerContext context() {
        return new AnswerContext("workspace", "conversation", "message", "query",
                Set.of(), Map.of(), Instant.now());
    }

    private EvidenceBundle bundle(List<EvidenceBundle.Evidence> evidence, String planVersion) {
        return new EvidenceBundle("bundle", planVersion, evidence, false, List.of(), Instant.now());
    }

    private EvidenceBundle noteBundle(EvidenceBundle source) {
        ReadingWindow window = new ReadingWindow(
                "chunk", "source", "snapshot", 0, "Heading", "Doc", "", "", 0,
                EXCERPT, "chunk:0", 9, "primary-window", 0, "best-evidence");
        CandidateSource candidate = new CandidateSource(
                "source", "Doc", "TEXT", 1, 1, "", "", "summary",
                "[]", "{}", "sample", 9, "", List.of(), List.of(), 0, 0, "", "");
        NoteRecallPlan plan = new NoteRecallPlan(
                List.of(), List.of(candidate), List.of(), List.of(candidate),
                new NoteRecallTrace(0, 1, 0, 1, 0, 0, 0, 0));
        String encoded = noteCodec().encode(new NoteRetrievalSnapshot(plan, List.of(), List.of(window)));
        return new EvidenceBundle("note-bundle", NoteAnswerModeStrategy.PLAN_VERSION,
                source.evidence().stream()
                        .map(item -> new EvidenceBundle.Evidence(
                                "note-window:chunk:0", "PASSAGE", "source", "snapshot", "chunk",
                                "", "", "Doc", EXCERPT, "chunk:0", 9, 9, 9,
                                "workspace-source:source", Instant.now(), "primary-window",
                                EXCERPT.length(), Map.of()))
                        .toList(),
                false, List.of(), Instant.now(),
                Map.of(NoteRetrievalSnapshotCodec.METADATA_KEY, encoded));
    }

    private EvidenceBundle wikiBundle(EvidenceBundle source) {
        WikiPageContext context = new WikiPageContext(
                new KnowledgePageHit("item", "version", 1, "Doc", "body", "summary", 9),
                List.of(), List.of(), List.of());
        String encoded = wikiCodec().encode(new WikiRetrievalSnapshot(List.of(context), List.of()));
        return new EvidenceBundle("wiki-bundle", WikiAnswerModeStrategy.PLAN_VERSION,
                List.of(new EvidenceBundle.Evidence(
                        "knowledge-version:version", "KNOWLEDGE_VERSION", "", "", "",
                        "item", "version", "Doc", EXCERPT, "knowledge-version:version",
                        9, 9, 9, "workspace-knowledge:item", Instant.now(), "wiki-page-graph",
                        EXCERPT.length(), Map.of())),
                false, List.of(), Instant.now(),
                Map.of(WikiRetrievalSnapshotCodec.METADATA_KEY, encoded));
    }

    private EvidenceBundle.Evidence evidence(String id, String title, String excerpt) {
        return evidence(id, title, excerpt, excerpt.length());
    }

    private EvidenceBundle.Evidence evidence(String id, String title, String excerpt, int cost) {
        return new EvidenceBundle.Evidence(
                id, "PASSAGE", "source", "snapshot", "chunk", "", "", title,
                excerpt, "loc", 9, 9, 9, "workspace-source:source", Instant.now(),
                "selected", cost, Map.of());
    }

    private NoteRetrievalSnapshotCodec noteCodec() {
        return new NoteRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
    }

    private NoteAnswerModeStrategy noteStrategy() {
        return new NoteAnswerModeStrategy(noteCodec());
    }

    private WikiRetrievalSnapshotCodec wikiCodec() {
        return new WikiRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
    }

    private WikiAnswerModeStrategy wikiStrategy() {
        return new WikiAnswerModeStrategy(wikiCodec());
    }
}
