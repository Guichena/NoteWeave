package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.NoteEntryMetadata;
import com.noteweave.chat.NoteRetrievalService.NoteJournalHit;
import com.noteweave.chat.NoteRetrievalService.NoteRecallPlan;
import com.noteweave.chat.NoteRetrievalService.NoteRecallTrace;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoteAnswerModeStrategyTest {

    private final NoteRetrievalSnapshotCodec codec =
            new NoteRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
    private final NoteAnswerModeStrategy strategy = new NoteAnswerModeStrategy(codec);

    @Test
    void shouldReplayLegacyNoteTemplateFromBundleSnapshot() {
        NoteRetrievalSnapshot snapshot = new NoteRetrievalSnapshot(
                recallPlan(),
                List.of(metadata()),
                List.of(window())
        );
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle",
                NoteAnswerModeStrategy.PLAN_VERSION,
                List.of(evidence()),
                false,
                List.of(),
                Instant.now(),
                Map.of(NoteRetrievalSnapshotCodec.METADATA_KEY, codec.encode(snapshot))
        );
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "retrieval query", Set.of(),
                Map.ofEntries(
                        Map.entry(NoteAnswerModeStrategy.ATTRIBUTE_REQUEST_CONTENT, "当前问题"),
                        Map.entry(NoteAnswerModeStrategy.ATTRIBUTE_QUESTION_TYPE, "reasoning"),
                        Map.entry(NoteAnswerModeStrategy.ATTRIBUTE_CONTEXT_APPLIED, "true"),
                        Map.entry(NoteAnswerModeStrategy.ATTRIBUTE_WINDOW_TURN_COUNT, "2"),
                        Map.entry(NoteAnswerModeStrategy.ATTRIBUTE_TOPIC_ANCHOR, "NoteTopic"),
                        Map.entry(NoteAnswerModeStrategy.ATTRIBUTE_TOPIC_SUMMARY, "前序摘要"),
                        Map.entry(NoteAnswerModeStrategy.ATTRIBUTE_HAS_CHAT_CONTROLS, "true"),
                        Map.entry(NoteAnswerModeStrategy.ATTRIBUTE_CHAT_CONTROL_SECTION,
                                "## 表达控制\n- 风格约束：简洁\n\n")
                ),
                Instant.now()
        );

        var prompt = strategy.compose(context, bundle);

        assertThat(prompt.referencedEvidenceIds()).containsExactly("note-window:chunk:0");
        assertThat(prompt.userPrompt())
                .contains("用户请求：")
                .contains("## 直接回答")
                .contains("## 资料定位")
                .contains("## 摘录证据")
                .contains("[1] 《")
                .doesNotContain("verify_batch_sources", "【关系扩展】", "Marginalia 式结构化检索漏斗")
                .contains("## 表达控制\n- 风格约束：简洁");
        assertThat(strategy.plan(context).version()).isEqualTo("note-marginalia-funnel-v1");
    }

    @Test
    void shouldRenderOnlyWindowsThatRemainInEvidenceBundle() {
        ReadingWindow selected = window();
        ReadingWindow excluded = new ReadingWindow(
                "chunk-excluded", "source-excluded", "snapshot-excluded", 1,
                "Excluded Heading", "Excluded Source", "", "", 0,
                "excluded window content", "chunk:1", 8,
                "continuation-window", 0, "verify"
        );
        NoteRetrievalSnapshot snapshot = new NoteRetrievalSnapshot(
                recallPlan(), List.of(metadata()), List.of(selected, excluded));
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", NoteAnswerModeStrategy.PLAN_VERSION, List.of(evidence()), false,
                List.of(), Instant.now(),
                Map.of(NoteRetrievalSnapshotCodec.METADATA_KEY, codec.encode(snapshot))
        );
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(), Map.of(), Instant.now());

        var prompt = strategy.compose(context, bundle);

        assertThat(prompt.userPrompt())
                .contains("window content")
                .doesNotContain("excluded window content")
                .doesNotContain("Excluded Source");
        assertThat(prompt.referencedEvidenceIds()).containsExactly("note-window:chunk:0");
    }

    @Test
    void finalPromptOnlyUsesFinalWindowsAndDropsJournalAndUnselectedSources() {
        CandidateSource selected = candidate();
        CandidateSource unselected = new CandidateSource(
                "source-unselected", "Unselected Source", "MARKDOWN", 1, 1, "", "",
                "unselected summary", "[]", "{}", "sample", 3, "metadata", List.of(), List.of(),
                0, 0, "metadata-quota", "candidate:metadata-quota");
        NoteJournalHit invalidJournal = new NoteJournalHit(
                "note", "History Title", "history summary", "history content", 1, 4,
                "source-deleted", "deleted", 1, 0);
        NoteRecallPlan plan = new NoteRecallPlan(
                List.of(invalidJournal),
                List.of(selected, unselected),
                List.of(),
                List.of(selected),
                new NoteRecallTrace(1, 2, 0, 1, 2, 3, 0, 1));
        NoteRetrievalSnapshot snapshot = new NoteRetrievalSnapshot(
                plan, List.of(metadata()), List.of(window()));
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", NoteAnswerModeStrategy.PLAN_VERSION, List.of(evidence()), false,
                List.of(), Instant.now(),
                Map.of(NoteRetrievalSnapshotCodec.METADATA_KEY, codec.encode(snapshot))
        );
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(), Map.of(), Instant.now());

        var prompt = strategy.compose(context, bundle);

        assertThat(prompt.userPrompt())
                .contains("## 资料定位", "《Source》", "window content")
                // 失效日志标题、未入选来源标题与摘要都不能继续供模型回答
                .doesNotContain("History Title", "history summary")
                .doesNotContain("Unselected Source", "unselected summary");
    }

    @Test
    void noFinalWindowIsInsufficientEvenWhenCandidateSourcesExist() {
        NoteRetrievalSnapshot snapshot = new NoteRetrievalSnapshot(
                recallPlan(), List.of(metadata()), List.of());
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", NoteAnswerModeStrategy.PLAN_VERSION, List.of(), false,
                List.of(), Instant.now(),
                Map.of(NoteRetrievalSnapshotCodec.METADATA_KEY, codec.encode(snapshot))
        );
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(), Map.of(), Instant.now());

        var prompt = strategy.compose(context, bundle);

        assertThat(prompt.refusal()).isTrue();
        assertThat(prompt.userPrompt()).contains("资料不足");
    }

    private NoteRecallPlan recallPlan() {
        CandidateSource candidate = candidate();
        return new NoteRecallPlan(
                List.of(new NoteJournalHit(
                        "note", "History", "summary", "content", 1, 4,
                        "fresh", "current", 0, 0)),
                List.of(candidate),
                List.of(),
                List.of(candidate),
                new NoteRecallTrace(1, 1, 0, 1, 2, 3, 0, 1)
        );
    }

    private CandidateSource candidate() {
        return new CandidateSource(
                "source", "Source", "MARKDOWN", 1, 1, "", "", "summary",
                "[]", "{}", "sample", 8, "metadata", List.of("title"),
                List.of("NoteTopic"), 1, 1, "metadata-quota", "candidate:metadata-quota"
        );
    }

    private NoteEntryMetadata metadata() {
        return new NoteEntryMetadata(
                "source", "Source", "MARKDOWN", "", "", "summary", List.of("tag"),
                List.of("title=Source"), "SUCCEEDED", "INDEXED", 1, 1, false,
                List.of(), List.of()
        );
    }

    private ReadingWindow window() {
        return new ReadingWindow(
                "chunk", "source", "snapshot", 0, "Heading", "Source", "", "",
                0, "window content", "chunk:0", 9, "anchor-window", 0, "verify"
        );
    }

    private EvidenceBundle.Evidence evidence() {
        return new EvidenceBundle.Evidence(
                "note-window:chunk:0", "PASSAGE", "source", "snapshot", "chunk",
                "", "", "Source", "window content", "chunk:0", 9, 1, 1,
                "workspace-source:source", null, "anchor-window", 14, Map.of()
        );
    }
}
