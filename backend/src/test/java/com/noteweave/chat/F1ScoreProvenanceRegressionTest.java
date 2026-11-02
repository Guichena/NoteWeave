package com.noteweave.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noteweave.answer.EvidenceSufficiency;
import com.noteweave.answer.strategy.*;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiPageContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class F1ScoreProvenanceRegressionTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @CsvSource({"0,INSUFFICIENT", "0.01,INSUFFICIENT", "0.149,INSUFFICIENT", "0.15,PARTIAL", "0.349,PARTIAL", "0.35,SUFFICIENT"})
    void onlyExplicitlyCalibratedProviderUsesThreshold(double value, EvidenceSufficiency expected) {
        ScoreInfo calibrated = ScoreInfo.provider("actual-model", value, ScoreInfo.UNIT_INTERVAL, ScoreInfo.KNOWN_PROVIDER_015_035);
        assertThat(EvidenceSufficiencyJudge.combine(EvidenceSufficiency.SUFFICIENT, List.of(evidence(calibrated))).verdict()).isEqualTo(expected);
        assertThat(EvidenceSufficiencyJudge.combine(EvidenceSufficiency.SUFFICIENT,
                List.of(evidence(ScoreInfo.provider("noteweave-rerank", value)))).verdict()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(EvidenceSufficiencyJudge.combine(EvidenceSufficiency.SUFFICIENT,
                List.of(evidence(ScoreInfo.llmFallback("llm-listwise-v1", value)))).verdict()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
    }

    @Test
    void evidenceSnapshotV2RoundTripsAllFieldsAndV1RemainsUnknown() throws Exception {
        ScoreInfo info = ScoreInfo.provider("actual", 0.149, ScoreInfo.UNIT_INTERVAL, ScoreInfo.KNOWN_PROVIDER_015_035);
        EvidenceBundle bundle = new EvidenceBundle("b", "p", List.of(evidence(info)), false, List.of(), Instant.now());
        EvidenceBundleSnapshot snapshot = EvidenceBundleSnapshot.from(bundle);
        String json = mapper.writeValueAsString(snapshot);
        assertThat(snapshot.schemaVersion()).isEqualTo("evidence-bundle-snapshot-v2");
        assertThat(mapper.readValue(json, EvidenceBundleSnapshot.class).evidence().get(0).scoreInfo()).isEqualTo(info);
        ObjectNode legacy = (ObjectNode) mapper.readTree(json);
        legacy.put("schemaVersion", "evidence-bundle-snapshot-v1");
        ((ObjectNode) legacy.path("evidence").get(0)).remove("scoreInfo");
        ScoreInfo restored = mapper.treeToValue(legacy, EvidenceBundleSnapshot.class).evidence().get(0).scoreInfo();
        assertThat(restored.origin()).isEqualTo(ScoreInfo.LEGACY_UNKNOWN);
        assertThat(restored.value()).isNull();
    }

    @Test
    void noteSourceAndWindowSnapshotsKeepIndependentOrigins() throws Exception {
        ScoreInfo sourceInfo = ScoreInfo.llmFallback("llm-listwise-v1", 0.01);
        ScoreInfo windowInfo = ScoreInfo.provider("window-provider", 0.349);
        var source = new NoteRetrievalService.CandidateSource("s", "title", "PDF", 1, 1, "", "", "summary", "[]", "{}", "sample", 1, "", List.of(), List.of(), 0, 0, "", "")
                .withSourceScoreInfo(sourceInfo).withScore(4).withSelectionReason("selected").withVerifyAdmissionReason("verify");
        var window = new NoteRetrievalService.ReadingWindow("c", "s", "snap", 0, "heading", "title", "", "", 0, "body", "loc", 1, "role", 0, "objective")
                .withRerankScore(0.349, windowInfo).withScore(4).withRawScore(8).withReadRole("primary").withAnchorWindowNo(1).withReadObjective("read");
        var plan = new NoteRetrievalService.NoteRecallPlan(List.of(), List.of(source), List.of(), List.of(source), null);
        var codec = new NoteRetrievalSnapshotCodec(mapper);
        var restored = codec.decode(codec.encode(new NoteRetrievalSnapshot(plan, List.of(), List.of(window))));
        assertThat(restored.recallPlan().verifySources().get(0).sourceScoreInfo()).isEqualTo(sourceInfo);
        assertThat(restored.windows().get(0).scoreInfo()).isEqualTo(windowInfo);
        ObjectNode legacy = (ObjectNode) mapper.readTree(codec.encode(new NoteRetrievalSnapshot(plan, List.of(), List.of(window))));
        ((ObjectNode) legacy.path("windows").get(0)).remove("scoreInfo");
        assertThat(codec.decode(legacy.toString()).windows().get(0).scoreInfo().origin()).isEqualTo(ScoreInfo.LEGACY_UNKNOWN);
    }

    @Test
    void wikiSnapshotKeepsRawDoubleSeparateFromDisplayInteger() {
        ScoreInfo info = ScoreInfo.provider("wiki-provider", 0.149);
        var page = new KnowledgePageHit("i", "v", 1, "title", "body", "summary", 15, info);
        var codec = new WikiRetrievalSnapshotCodec(mapper);
        var restored = codec.decode(codec.encode(new WikiRetrievalSnapshot(
                List.of(new WikiPageContext(page, List.of(), List.of(), List.of())), List.of())));
        assertThat(restored.contexts().get(0).page().scoreInfo()).isEqualTo(info);
        assertThat(restored.contexts().get(0).page().score()).isEqualTo(15);
    }

    private EvidenceBundle.Evidence evidence(ScoreInfo info) {
        return new EvidenceBundle.Evidence("e", "PASSAGE", "s", "snap", "c", "", "", "title", "body", "loc", 9, 9, 9, "scope", Instant.now(), "selected", 4, Map.of(), info);
    }
}
