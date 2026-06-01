package com.noteweave.studio.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.studio.dto.StudioSkillResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/studio/skills")
public class StudioSkillController {

    @GetMapping
    public ApiResponse<List<StudioSkillResponse>> list() {
        return ApiResponse.success(List.of(
                StudioSkillResponse.builder()
                        .id("study-guide")
                        .name("学习指南")
                        .artifactType("STUDY_GUIDE")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .entryType("artifact_skill")
                        .mcpToolName(null)
                        .argSchemaHint(null)
                        .description("把项目资料整理成学习路径、重点概念、例子和复习清单。")
                        .topicHint("考试复习、入职学习、课程提纲")
                        .build(),
                StudioSkillResponse.builder()
                        .id("research-report")
                        .name("研究报告")
                        .artifactType("REPORT")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .entryType("artifact_skill")
                        .mcpToolName(null)
                        .argSchemaHint(null)
                        .description("生成带结论、取舍和证据建议的结构化研究报告。")
                        .topicHint("决策备忘、调研报告、技术方案")
                        .build(),
                StudioSkillResponse.builder()
                        .id("comparison-analysis")
                        .name("对比分析")
                        .artifactType("COMPARISON")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .entryType("artifact_skill")
                        .mcpToolName(null)
                        .argSchemaHint(null)
                        .description("比较方案、工具或资料，突出差异、风险和适用场景。")
                        .topicHint("工具选型、方案比较、方法对照")
                        .build(),
                StudioSkillResponse.builder()
                        .id("work-prep")
                        .name("工作准备")
                        .artifactType("WORK_PREP")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .entryType("artifact_skill")
                        .mcpToolName(null)
                        .argSchemaHint(null)
                        .description("产出面向执行的准备材料，包含背景、行动项、开放问题和下一步。")
                        .topicHint("迭代启动、客户会议、面试准备")
                        .build(),
                StudioSkillResponse.builder()
                        .id("reading-notes")
                        .name("阅读笔记")
                        .artifactType("READING_NOTES")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .entryType("artifact_skill")
                        .mcpToolName(null)
                        .argSchemaHint(null)
                        .description("把资料压缩成可复用笔记，保留重点、问题和后续阅读方向。")
                        .topicHint("论文、文章组、主题资料")
                        .build(),
                StudioSkillResponse.builder()
                        .id("bilibili-notes")
                        .name("B 站笔记")
                        .artifactType("READING_NOTES")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .entryType("mcp_tool")
                        .mcpToolName("bilibili")
                        .argSchemaHint("params.mcpToolName=bilibili; params.mcpArgs.url=<bilibili-link>")
                        .description("读取 B 站链接的元数据和字幕，再生成结构化笔记。")
                        .topicHint("输入 B 站链接，可补充主题")
                        .build()
        ));
    }
}
