package com.noteweave.research;

import java.util.Set;

/** Research Agent 角色的分类，供任务创建、快照、完成提交共用，避免各处各写一份角色列表。 */
final class ResearchAgentRoles {
    static final String PLANNING = "PLANNING";
    static final String WIDE_DISCOVERY = "WIDE_DISCOVERY";

    /** 不绑定任何单元格的角色：它们产出的是对表格结构的提案，而不是某个单元格的值。 */
    private static final Set<String> CELL_LESS = Set.of(PLANNING, WIDE_DISCOVERY);

    /** 以 ROLE_RESULT 方式提交结果的角色。 */
    private static final Set<String> ROLE_RESULT = Set.of(PLANNING, WIDE_DISCOVERY, "EVIDENCE_AUDIT", "SYNTHESIS");

    private ResearchAgentRoles() {
    }

    static boolean ownsNoCells(String role) {
        return CELL_LESS.contains(role);
    }

    static boolean submitsRoleResult(String role) {
        return ROLE_RESULT.contains(role);
    }
}
