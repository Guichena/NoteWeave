package com.noteweave.personal.generation.service;

import com.noteweave.artifact.model.ArtifactSourceType;
import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.studio.service.ArtifactGenerateTaskInput;
import com.noteweave.studio.service.StudioMcpToolRegistry;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PersonalGenerationService {

    private final ResearchContextService researchContextService;
    private final PersonalEvidenceService personalEvidenceService;
    private final StudioMcpToolRegistry studioMcpToolRegistry;

    public PersonalGenerationPreparation prepare(Long userId, ArtifactGenerateTaskInput input) {
        Long projectId = input.getResearchProjectId() != null ? input.getResearchProjectId() : firstId(input.getSourceIds());
        ResearchGenerationContext context = researchContextService.load(userId, projectId, input.getArtifactType(), input.getParams());
        List<PersonalEvidenceItem> evidenceItems = hasMcpTool(input) && context.articleCards().isEmpty() && context.conceptCards().isEmpty()
                ? List.of()
                : personalEvidenceService.buildEvidence(context);

        List<SourceRef> sourceRefs = new ArrayList<>();
        context.articleCards().forEach(card -> sourceRefs.add(new SourceRef(ArtifactSourceType.ARTICLE_CARD, card.getId())));
        context.conceptCards().forEach(card -> sourceRefs.add(new SourceRef(ArtifactSourceType.CONCEPT_CARD, card.getId())));
        context.synthesisCards().forEach(card -> sourceRefs.add(new SourceRef(ArtifactSourceType.SYNTHESIS_CARD, card.getId())));
        evidenceItems.stream()
                .map(PersonalEvidenceItem::source)
                .filter(java.util.Objects::nonNull)
                .map(source -> new SourceRef(ArtifactSourceType.SOURCE, source.getId()))
                .forEach(sourceRefs::add);
        return new PersonalGenerationPreparation(context, evidenceItems, sourceRefs);
    }

    private boolean hasMcpTool(ArtifactGenerateTaskInput input) {
        return studioMcpToolRegistry.hasConfiguredTool(input.getParams());
    }

    public String instructionFor(ArtifactType artifactType) {
        return switch (artifactType) {
            case REPORT -> "生成研究报告，包含标题、摘要、背景、核心概念、关键发现、结论和引用来源。";
            case STUDY_GUIDE -> "生成学习指南，包含学习目标、前置知识、核心概念路径、阶段计划、自测问题和引用来源。";
            case COMPARISON -> "生成对比分析，包含对比对象、评价维度、适用场景、权衡结论和引用来源。";
            case WORK_PREP -> "生成工作准备材料，使用 STAR 或等价结构组织表达框架、核心回答、追问准备、项目亮点和风险改进。";
            case READING_NOTES -> "生成阅读笔记，突出认知变化、关键摘录、关联概念、个人反思和引用来源。";
            default -> throw new IllegalArgumentException("Unsupported personal artifact type: " + artifactType);
        };
    }

    private Long firstId(List<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return null;
        }
        return sourceIds.get(0);
    }

    public record PersonalGenerationPreparation(
            ResearchGenerationContext context,
            List<PersonalEvidenceItem> evidenceItems,
            List<SourceRef> sourceRefs
    ) {
    }

    public record SourceRef(ArtifactSourceType sourceType, Long sourceId) {
    }
}
