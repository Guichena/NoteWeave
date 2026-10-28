package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** 在版本提交前核对 Worker 的最终校验诊断，历史记录读取不受此门控影响。 */
final class ArtifactCandidateVerificationPolicy {
    private ArtifactCandidateVerificationPolicy() {}

    static void validatePipeline(Map<String, Object> payload, String expectedKind, Set<String> sourceIds) {
        require(payload != null && payload.get("ir") instanceof Map<?, ?>, "候选产物缺少新流水线内容");
        require(payload.get("verification") instanceof Map<?, ?> verification
                && "PASS".equals(verification.get("status")) && "PASS".equals(verification.get("contract_status"))
                && "PASS".equals(verification.get("evidence_status")), "新流水线产物未通过最终校验");
        Map<?, ?> ir = (Map<?, ?>) payload.get("ir");
        require(Integer.valueOf(1).equals(ir.get("schema_version")) && expectedKind.equals(ir.get("kind")),
                "产物 Schema 版本或形态与任务不一致");
        require(ir.get("title") instanceof String title && !title.isBlank(), "产物缺少标题");
        require(payload.get("content_ir") instanceof Map<?, ?> old && ir.get("title").equals(old.get("title")),
                "产物标题与阅读页投影不一致");
        require(payload.get("evidence") instanceof Map<?, ?> pack && pack.get("sentences") instanceof List<?>,
                "候选产物缺少素材包");
        List<?> sentences = (List<?>) ((Map<?, ?>) payload.get("evidence")).get("sentences");
        require(!sentences.isEmpty(), "素材包为空");
        Map<String, String> originals = new java.util.HashMap<>();
        for (Object raw : sentences) {
            require(raw instanceof Map<?, ?>, "素材句子格式无效");
            Map<?, ?> sentence = (Map<?, ?>) raw;
            require(sentence.get("id") instanceof String id && !id.isBlank()
                    && sentence.get("text") instanceof String text && !text.isBlank()
                    && sentence.get("chunk_id") instanceof String chunk && !chunk.isBlank()
                    && sourceIds.contains(sentence.get("source_id")), "素材句子缺少编号、正文或合法来源");
            require(originals.putIfAbsent((String) sentence.get("id"), (String) sentence.get("text")) == null,
                    "素材句子编号重复");
        }
        String collection = Map.of("DOC", "sections", "QUIZ", "questions", "CARDS", "cards", "TREE", "root", "TABLE", "rows", "DECK", "slides").get(expectedKind);
        require(collection != null, "产物形态无效");
        if (expectedKind.equals("TREE")) {
            require(ir.get("root") instanceof Map<?, ?> root && root.get("children") instanceof List<?> children && !children.isEmpty(),
                    "思维导图缺少节点");
        } else {
            require(ir.get(collection) instanceof List<?> items && !items.isEmpty(), "产物缺少内容单元");
        }
        validateFrames(ir, sentences);
        require(validateAnchors(ir, originals) > 0, "产物缺少带引用的事实内容");
        require(payload.get("pipeline_stats") instanceof Map<?, ?> stats
                && stats.get("stages") instanceof Map<?, ?> stages
                && stages.keySet().equals(Set.of("gather", "plan", "draft", "check", "render"))
                && stats.get("repair_calls") instanceof Number repair && repair.intValue() >= 0 && repair.intValue() <= 1,
                "流水线阶段或修复次数无效");
    }

    private static void validateFrames(Object node, List<?> sentences) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("frame_id")) {
                require(map.get("frame_id") instanceof String id && !id.isBlank()
                        && map.get("caption") instanceof Map<?, ?>, "关键帧引用或图注无效");
                Map<?, ?> caption = (Map<?, ?>) map.get("caption");
                require(caption.get("anchors") instanceof List<?>, "关键帧图注缺少引用");
                List<?> anchors = (List<?>) caption.get("anchors");
                require(sentences.stream().anyMatch(raw -> raw instanceof Map<?, ?> sentence
                        && anchors.contains(sentence.get("id")) && sentence.get("frame_ids") instanceof List<?> frames
                        && frames.contains(map.get("frame_id"))), "插图不属于图注引用的素材关键帧");
            }
            for (Object child : map.values()) validateFrames(child, sentences);
        } else if (node instanceof List<?> list) {
            for (Object child : list) validateFrames(child, sentences);
        }
    }

    private static int validateAnchors(Object node, Map<String, String> originals) {
        int facts = 0;
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("anchors")) {
                require(map.get("text") instanceof String text && !text.isBlank()
                        && map.get("anchors") instanceof List<?> anchors && !anchors.isEmpty()
                        && anchors.stream().allMatch(originals::containsKey), "产物引用了素材包以外的句子");
                List<?> anchors = (List<?>) map.get("anchors");
                require(anchors.size() == Set.copyOf(anchors).size(), "产物引用包含重复编号");
                java.util.regex.Pattern numbers = java.util.regex.Pattern.compile("[+-]?[0-9]+(?:[.,][0-9]+)*(?:%|％)?");
                String original = anchors.stream().map(originals::get).collect(java.util.stream.Collectors.joining(" "));
                Set<String> allowed = numbers.matcher(original).results().map(java.util.regex.MatchResult::group)
                        .collect(java.util.stream.Collectors.toSet());
                require(numbers.matcher((String) map.get("text")).results().allMatch(m -> allowed.contains(m.group())),
                        "产物事实中的数字与原句不一致");
                facts++;
            }
            for (Object child : map.values()) facts += validateAnchors(child, originals);
        } else if (node instanceof List<?> list) {
            for (Object child : list) facts += validateAnchors(child, originals);
        }
        return facts;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new BusinessException("ARTIFACT_CONTENT_NOT_VERIFIED", message, HttpStatus.CONFLICT);
    }
}
