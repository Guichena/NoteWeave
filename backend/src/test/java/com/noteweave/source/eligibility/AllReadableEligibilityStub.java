package com.noteweave.source.eligibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试桩：所有来源按 READY 返回，生成依赖闭包为空。
 * 供沿用旧门控行为的单元测试使用，不进入生产代码。
 */
public final class AllReadableEligibilityStub implements MaterialEligibilityPort {

    private volatile boolean allRevoked = false;

    /** 模拟 COMPLIANCE 撤销：所有来源按已撤销返回，历史与证据均不可读。 */
    public void revokeAll() {
        this.allRevoked = true;
    }

    @Override
    public Map<String, EligibilityDecision> assess(
            String workspaceId, List<OriginRef> origins, AccessPurpose purpose) {
        Map<String, EligibilityDecision> decisions = new LinkedHashMap<>();
        for (OriginRef origin : origins) {
            decisions.put(origin.originKey(), allRevoked
                    ? MaterialEligibilityPolicy.decide(origin.originKey(),
                            EligibilityStatus.COMPLIANCE_REVOKED, "TEST_REVOKED", null, null, List.of())
                    : EligibilityDecision.ready(origin.originKey(), 1L, origin.snapshotId()));
        }
        return decisions;
    }

    @Override
    public GeneratedDependencyAssessment assessGeneratedDependenciesDetailed(
            String workspaceId, String generatedBy, String generatedRefId, AccessPurpose purpose) {
        return new GeneratedDependencyAssessment(true, List.of(), Map.of());
    }
}
