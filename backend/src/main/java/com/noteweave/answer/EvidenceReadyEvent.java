package com.noteweave.answer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.noteweave.answer.generation.CitationBinding;
import com.noteweave.answer.generation.GenerationContract;
import java.util.List;

/**
 * 流式引用协议事件 {@code evidence.ready} 的载荷。
 *
 * <p>在首个正文增量之前下发，把冻结契约里的允许集合与编号绑定交给前端，让 {@code [n]} 角标
 * 从流式第一段正文起就能按绑定渲染。字段名与前端 {@code parseCitationProtocolEvent}
 * 的解析保持一致，两边只改一处就会失效，改这里要同步改前端。</p>
 *
 * <p>历史运行没有冻结契约，此时不发该事件，前端按旧协议解析。</p>
 */
public record EvidenceReadyEvent(
        @JsonProperty("protocol_version") String protocolVersion,
        @JsonProperty("allowed_evidence_ids") List<String> allowedEvidenceIds,
        @JsonProperty("citation_bindings") List<Binding> citationBindings
) {

    /** 事件名，与前端 {@code evidence.ready} 分支一致。 */
    public static final String EVENT_TYPE = "evidence.ready";

    /** 协议版本，与证据清单接口的 protocolVersion 取同一个值。 */
    public static final String PROTOCOL_VERSION = AnswerEvidenceManifestResponse.PROTOCOL_VERSION;

    public EvidenceReadyEvent {
        allowedEvidenceIds = allowedEvidenceIds == null ? List.of() : List.copyOf(allowedEvidenceIds);
        citationBindings = citationBindings == null ? List.of() : List.copyOf(citationBindings);
    }

    /** 用冻结契约构造；契约里编号绑定的顺序即正文里 [n] 的顺序。 */
    public static EvidenceReadyEvent from(GenerationContract contract) {
        if (contract == null) {
            return null;
        }
        return new EvidenceReadyEvent(
                PROTOCOL_VERSION,
                contract.allowedEvidenceIds(),
                contract.citationBindings().stream().map(EvidenceReadyEvent::bindingOf).toList()
        );
    }

    private static Binding bindingOf(CitationBinding binding) {
        return new Binding(binding.number(), binding.evidenceId(), binding.citationIds());
    }

    /** 一条编号绑定，编号与正文里的 [n] 一致。 */
    public record Binding(
            @JsonProperty("number") int number,
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("citation_ids") List<String> citationIds
    ) {
        public Binding {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }
}