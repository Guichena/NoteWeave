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
                .contains("Marginalia 式结构化检索漏斗")
                .contains("【Journal 信号】")
                .contains("【候选资料】")
                .contains("【关系扩展】")
                .contains("verify_batch_sources: 1")
                .contains("## 深读窗口")
                .contains("## 摘录证据")
                .contains("本轮还应用了工作台级 Chat Control Pack。")
                .contains("## 表达控制\n- 风格约束：简洁");
        assertThat(strategy.plan(context).version()).isEqualTo("note-marginalia-v1");
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
