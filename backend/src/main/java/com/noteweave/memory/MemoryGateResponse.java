package com.noteweave.memory;

/**
 * 候选晋升时的门控打分与阈值。只有来源可信、效用足够、风险可控、范围有效且不与生效记忆冲突，
 * 候选才会直接生效；否则进入人工确认。
 */
public record MemoryGateResponse(
        String result,
        String sourceType,
        double evidenceScore,
        double evidenceThreshold,
        double utilityScore,
        double utilityThreshold,
        double riskScore,
        double riskThreshold,
        String scopeStatus,
        String conflictStatus,
        String policyVersion,
        // 候选的来源引用，例如从对话中提取时为 conversation-message:{消息 ID}:{序号}
        String sourceRef
) {
}
