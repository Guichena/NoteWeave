package com.noteweave.answer;

/** 数据库撤销提交后清理短期传输副本；审计身份不携带正文。 */
public record AnswerReplayRevoked(String workspaceId, String conversationId, String runId) {}
