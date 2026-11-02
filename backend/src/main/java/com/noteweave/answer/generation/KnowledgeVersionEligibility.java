package com.noteweave.answer.generation;

/** 知识版本当前取证资格端口，回答核心不依赖知识模块实现。 */
public interface KnowledgeVersionEligibility {
    void requireCurrentVersionEligible(String workspaceId, String knowledgeItemId, String versionId);
}
