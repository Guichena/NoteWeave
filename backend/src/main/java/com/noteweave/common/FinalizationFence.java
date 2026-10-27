package com.noteweave.common;

/**
 * 死信补偿的业务代次围栏（FB-01）。
 *
 * <p>补偿执行器在调用业务 finalizer 前为每条补偿构造围栏；finalizer 必须在
 * 同一个业务短事务内、更新 task/snapshot/batch 之前调用 {@link #stillCurrent()}：
 * 返回 false 说明登记时的代次事实已被人工重驱或新投递取代，本条补偿只能以
 * IGNORED_STALE 无副作用退出，绝不能把已经重驱的业务再打回失败。
 *
 * <p>放在 common 共享内核：infra 补偿执行器构造围栏，source/retrieval/memory 等
 * 业务 finalizer 消费围栏，双方都不能反向依赖对方实现包。
 *
 * <p>租约 fencing（claim_token 保护补偿回执）与业务代次 fencing（本接口保护业务状态）
 * 是两件事，必须同时成立：只保护回执不能阻止旧线程修改业务。
 */
public interface FinalizationFence {

    /** 在业务事务内调用：登记时的代次事实仍然成立返回 true；已被重驱/换代返回 false。 */
    boolean stillCurrent();
}
