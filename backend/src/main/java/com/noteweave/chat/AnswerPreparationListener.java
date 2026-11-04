package com.noteweave.chat;

import java.util.Map;

/**
 * 回答准备阶段的进度回调：发消息后到开始生成之间，按理解问题、检索、撰写几个阶段通知调用方，
 * 前端据此显示进度。回调只用于展示，实现方不能抛异常影响准备本身。
 */
@FunctionalInterface
public interface AnswerPreparationListener {

    /** 开始理解问题：改写查询、整理对话上下文。 */
    String STAGE_UNDERSTANDING = "understanding";
    /** 改写完成，开始检索资料。 */
    String STAGE_RETRIEVING = "retrieving";
    /** 检索完成，data 带 evidence_count。 */
    String STAGE_RETRIEVED = "retrieved";
    /** 准备完成，开始调用模型撰写正文。 */
    String STAGE_WRITING = "writing";

    AnswerPreparationListener NONE = (stage, data) -> { };

    void stage(String stage, Map<String, Object> data);
}
