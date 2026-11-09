package com.noteweave.retrieval;

import java.util.Set;

/**
 * 检索阶段提前排除的已撤销资料。
 * <p>
 * 只用于在 ES 查询里排除，让撤销后尚未删除的旧片段不占召回名额；
 * 资料是否可用仍以召回后的 MySQL 批量复核为准，这里读不到或读到旧数据都不影响正确性。
 */
public interface RevokedSourceFilter {

    RevokedSourceFilter NONE = workspaceId -> Set.of();

    Set<String> revokedSourceIds(String workspaceId);
}
