package com.noteweave.artifact;

import java.util.List;
import java.util.Map;

public record ArtifactSkillSummaryResponse(
        String skillKey,
        String displayName,
        String description,
        String status,
        Map<String, Object> inputSchema,
        List<String> defaultInputHints,
        // 产物卡片的展示信息（摘要、分组、排序、徽标等），来自技能目录
        Map<String, Object> presentation
) {
}
