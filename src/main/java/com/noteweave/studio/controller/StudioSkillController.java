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
                        .name("Study Guide")
                        .artifactType("STUDY_GUIDE")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .description("Turn a project corpus into a structured study guide with sections, examples, and review points.")
                        .topicHint("Exam prep, onboarding, or a learning track")
                        .build(),
                StudioSkillResponse.builder()
                        .id("research-report")
                        .name("Research Report")
                        .artifactType("REPORT")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .description("Produce a report-style artifact with findings, trade-offs, and evidence-backed recommendations.")
                        .topicHint("Decision memo or research report title")
                        .build(),
                StudioSkillResponse.builder()
                        .id("comparison-analysis")
                        .name("Comparison Analysis")
                        .artifactType("COMPARISON")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .description("Compare options, approaches, or sources and highlight meaningful differences and risks.")
                        .topicHint("Products, tools, methods, or proposals to compare")
                        .build(),
                StudioSkillResponse.builder()
                        .id("work-prep")
                        .name("Work Prep")
                        .artifactType("WORK_PREP")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .description("Prepare a concise execution brief with context, action items, open questions, and next steps.")
                        .topicHint("Sprint kickoff, customer meeting, or implementation prep")
                        .build(),
                StudioSkillResponse.builder()
                        .id("reading-notes")
                        .name("Reading Notes")
                        .artifactType("READING_NOTES")
                        .sourceScopeType("RESEARCH_PROJECT")
                        .description("Summarize source material into digestible notes with key points and follow-up questions.")
                        .topicHint("Paper, article set, or topic notes")
                        .build()
        ));
    }
}
