package com.noteweave.knowledge;

/**
 * READY 意图唤醒端口（WIKI-04）：READY 事务已经把摄取意图持久化到 source_ready_intent，
 * 提交后的监听只需要让调度器尽快认领，不能再自己建任务——否则意图路径和监听路径
 * 会给同一份资料各建一个任务。
 */
public interface WikiReadyIntentWakeupPort {

    /**
     * 把该资料待处理的 READY 意图拉到立即到期，交给调度器认领。
     *
     * @return 是否确实唤醒了待处理意图；false 表示没有可唤醒的意图（已消费、处理中或尚未登记）
     */
    boolean wake(String workspaceId, String sourceId);
}
