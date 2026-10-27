package com.noteweave.infra.outbox;

import com.noteweave.common.FinalizationFence;
import com.noteweave.common.FinalizationOutcome;
import java.util.Map;

/**
 * 业务模块向 outbox 补偿执行器注册的收尾适配器（B-01 / FB-01）。
 *
 * <p>业务包（research、memory 等）实现本接口并注册为 Spring bean，
 * 避免基础设施反向依赖业务模块。实现必须幂等：补偿可能在「业务已提交、
 * 结果未写回」的窗口后重复执行；只有数据库能证明已提交才返回 APPLIED。
 * 过期 lease 或旧 generation 的迟到执行只能产生无副作用回执（IGNORED_STALE），
 * 不能终止当前任务。
 */
public interface OutboxBusinessFinalizer {

    /** 本适配器承接的补偿业务类型，与补偿登记的 business_kind 一致。 */
    String businessKind();

    /**
     * 执行业务收尾。identity 是补偿登记时冻结的业务身份（不含全文载荷），
     * 实现方必须先核对身份与当前业务代次，再执行状态机收尾。
     *
     * <p>FB-01：fence 是执行器为本次补偿构造的业务代次围栏；实现方若更新业务状态，
     * 必须在同一个业务短事务内、更新之前调用 {@link FinalizationFence#stillCurrent()}
     * 复核，围栏失效时以 IGNORED_STALE 无副作用退出。实现自带更强围栏
     * （如 Research 按 deliveryNo 核对）时可以不使用传入围栏，但不得忽略代次核对本身。
     */
    FinalizationOutcome finalize(Map<String, Object> identity, FinalizationFence fence);
}
