package com.noteweave.research;

import com.noteweave.common.BusinessException;
import com.noteweave.common.FinalizationOutcome;
import com.noteweave.common.FinalizationFence;
import com.noteweave.infra.outbox.OutboxBusinessFinalizer;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Research agent 投递死信的补偿适配器（B-01）。
 *
 * <p>由 research 包向 infra 的补偿执行器注册，避免基础设施反向依赖业务模块。
 * 执行时先校验死信行（deliveryNo、任务匹配）仍然成立：旧 deliveryNo 的死信在
 * 新投递接管后校验失败，只能产生无副作用回执（IGNORED_STALE），不能终止新投递。
 * 本适配器以 deliveryNo 为业务代次围栏（强于执行器传入的 store 级围栏），故不重复核对。
 */
@Component
public class ResearchAgentDispatchFinalizer implements OutboxBusinessFinalizer {

    public static final String KIND = "RESEARCH_AGENT_COMMAND";

    private final ResearchAgentLifecycleService lifecycleService;

    public ResearchAgentDispatchFinalizer(ResearchAgentLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Override
    public String businessKind() {
        return KIND;
    }

    @Override
    public FinalizationOutcome finalize(Map<String, Object> identity, FinalizationFence fence) {
        String taskId = text(identity.get("taskId"));
        String outboxId = text(identity.get("outboxId"));
        if (taskId == null || outboxId == null) {
            return FinalizationOutcome.IGNORED_STALE;
        }
        Object attemptNo = identity.get("attemptNo");
        if (!(attemptNo instanceof Number number) || number.intValue() < 1) {
            return FinalizationOutcome.IGNORED_STALE;
        }
        try {
            boolean applied = lifecycleService.failCommandDispatchExhausted(
                    taskId, outboxId, number.intValue());
            return applied ? FinalizationOutcome.APPLIED : FinalizationOutcome.IGNORED_STALE;
        } catch (BusinessException business) {
            if ("RESEARCH_AGENT_OUTBOX_DEAD_LETTER_INVALID".equals(business.code())
                    || "RESEARCH_AGENT_TASK_NOT_FOUND".equals(business.code())) {
                // 死信行已不再是登记时的代次（新投递接管或任务不存在）：过期事实
                return FinalizationOutcome.IGNORED_STALE;
            }
            throw business;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
