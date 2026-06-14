package com.noteweave.personal.question.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.citation.model.Citation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.dto.CardCitationResponse;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimCitation;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.model.ClaimType;
import com.noteweave.personal.claim.repository.ClaimCitationRepository;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.dto.ResearchQuestionOverviewClaimResponse;
import com.noteweave.personal.question.dto.ResearchQuestionOverviewEvidenceResponse;
import com.noteweave.personal.question.dto.ResearchQuestionOverviewResponse;
import com.noteweave.personal.question.dto.ResearchQuestionOverviewSnapshotResponse;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.model.ResearchQuestionOverview;
import com.noteweave.personal.question.repository.ResearchQuestionOverviewRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResearchQuestionOverviewService {

    private static final TypeReference<List<ResearchQuestionOverviewClaimResponse>> KEY_CLAIMS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ResearchQuestionOverviewEvidenceResponse>> SUPPORTING_EVIDENCE_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<ResearchQuestionOverviewSnapshotResponse> SNAPSHOT_TYPE = new TypeReference<>() {
    };

    private final ResearchQuestionService researchQuestionService;
    private final ResearchQuestionOverviewRepository researchQuestionOverviewRepository;
    private final ClaimRepository claimRepository;
    private final ClaimConceptRelationRepository claimConceptRelationRepository;
    private final ClaimCitationRepository claimCitationRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final CitationRepository citationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ResearchQuestionOverviewResponse generate(Long userId, Long questionId) {
        ResearchQuestion question = researchQuestionService.getRequiredQuestion(userId, questionId);
        OverviewDocument document = buildDocument(question);

        ResearchQuestionOverview overview = new ResearchQuestionOverview();
        overview.setSpaceId(question.getSpaceId());
        overview.setUserId(question.getUserId());
        overview.setResearchProjectId(question.getResearchProjectId());
        overview.setResearchQuestionId(question.getId());
        overview.setTitle(question.getTitle());
        overview.setSummary(document.summary());
        overview.setCurrentAnswer(document.currentAnswer());
        overview.setKeyClaimsJson(writeJson(document.keyClaims()));
        overview.setSupportingEvidenceJson(writeJson(document.supportingEvidence()));
        overview.setConflictsJson(writeJson(document.conflicts()));
        overview.setOpenIssuesJson(writeJson(document.openIssues()));
        overview.setNextStepsJson(writeJson(document.nextSteps()));
        overview.setMarkdown(document.markdown());
        overview.setGeneratedFromSnapshotJson(writeJson(document.snapshot()));

        ResearchQuestionOverview saved = researchQuestionOverviewRepository.save(overview);
        return toResponse(saved, true);
    }

    @Transactional(readOnly = true)
    public ResearchQuestionOverviewResponse getLatest(Long userId, Long questionId) {
        ResearchQuestion question = researchQuestionService.getRequiredQuestion(userId, questionId);
        return researchQuestionOverviewRepository.findTopByResearchQuestionIdOrderByUpdatedAtDesc(questionId)
                .map(overview -> toResponse(overview, true))
                .orElseGet(() -> emptyResponse(question));
    }

    private OverviewDocument buildDocument(ResearchQuestion question) {
        List<Claim> allClaims = claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(question.getId());
        List<Claim> currentClaims = filterCurrentClaims(allClaims);
        Map<Long, List<ClaimConceptRelation>> relationsByClaimId = loadConceptRelations(currentClaims);
        Map<Long, List<ClaimCitation>> citationsByClaimId = loadCitationRelations(currentClaims);
        Map<Long, String> conceptNamesById = loadConceptNames(relationsByClaimId.values());
        Map<Long, Citation> citationsById = loadCitations(citationsByClaimId.values());

        List<ResearchQuestionOverviewClaimResponse> keyClaims = buildKeyClaims(currentClaims, relationsByClaimId, citationsByClaimId, conceptNamesById);
        List<ResearchQuestionOverviewEvidenceResponse> supportingEvidence = buildSupportingEvidence(currentClaims, citationsByClaimId, citationsById);
        List<String> conflicts = detectConflicts(currentClaims, relationsByClaimId, conceptNamesById);
        List<String> openIssues = currentClaims.stream()
                .filter(claim -> claim.getClaimType() == ClaimType.OPEN_ISSUE)
                .map(Claim::getStatement)
                .toList();
        List<String> relatedConcepts = collectRelatedConcepts(relationsByClaimId, conceptNamesById);
        String currentAnswer = resolveCurrentAnswer(question, currentClaims);
        List<String> nextSteps = resolveNextSteps(question, openIssues, currentClaims);
        String summary = resolveSummary(currentClaims, openIssues, currentAnswer);
        ResearchQuestionOverviewSnapshotResponse snapshot = ResearchQuestionOverviewSnapshotResponse.builder()
                .claimIds(currentClaims.stream().map(Claim::getId).toList())
                .conceptCardIds(collectConceptIds(relationsByClaimId))
                .citationIds(collectCitationIds(citationsByClaimId))
                .sessionSummaryIds(List.of())
                .build();
        String markdown = buildMarkdown(question, currentAnswer, keyClaims, supportingEvidence, conflicts, openIssues, nextSteps, relatedConcepts);

        return new OverviewDocument(
                summary,
                currentAnswer,
                keyClaims,
                supportingEvidence,
                conflicts,
                openIssues,
                nextSteps,
                relatedConcepts,
                snapshot,
                markdown
        );
    }

    private List<Claim> filterCurrentClaims(List<Claim> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        Set<Long> superseded = claims.stream()
                .map(Claim::getSupersedesClaimId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return claims.stream()
                .filter(claim -> !superseded.contains(claim.getId()))
                .toList();
    }

    private Map<Long, List<ClaimConceptRelation>> loadConceptRelations(List<Claim> claims) {
        if (claims.isEmpty()) {
            return Map.of();
        }
        return claimConceptRelationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(claimIds(claims)).stream()
                .collect(Collectors.groupingBy(ClaimConceptRelation::getClaimId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, List<ClaimCitation>> loadCitationRelations(List<Claim> claims) {
        if (claims.isEmpty()) {
            return Map.of();
        }
        return claimCitationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(claimIds(claims)).stream()
                .collect(Collectors.groupingBy(ClaimCitation::getClaimId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, String> loadConceptNames(Collection<List<ClaimConceptRelation>> groupedRelations) {
        Set<Long> conceptIds = groupedRelations.stream()
                .flatMap(List::stream)
                .map(ClaimConceptRelation::getConceptCardId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (conceptIds.isEmpty()) {
            return Map.of();
        }
        return conceptCardRepository.findByIdIn(conceptIds).stream()
                .collect(Collectors.toMap(ConceptCard::getId, ConceptCard::getName, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<Long, Citation> loadCitations(Collection<List<ClaimCitation>> groupedCitations) {
        Set<Long> citationIds = groupedCitations.stream()
                .flatMap(List::stream)
                .map(ClaimCitation::getCitationId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (citationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Citation> citations = new LinkedHashMap<>();
        citationRepository.findAllById(citationIds).forEach(citation -> citations.put(citation.getId(), citation));
        return citations;
    }

    private List<ResearchQuestionOverviewClaimResponse> buildKeyClaims(
            List<Claim> currentClaims,
            Map<Long, List<ClaimConceptRelation>> relationsByClaimId,
            Map<Long, List<ClaimCitation>> citationsByClaimId,
            Map<Long, String> conceptNamesById
    ) {
        return currentClaims.stream()
                .filter(claim -> claim.getClaimType() != ClaimType.OPEN_ISSUE)
                .sorted(Comparator
                        .comparing((Claim claim) -> claimPriority(claim.getClaimType()))
                        .thenComparing(Claim::getConfidence, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Claim::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(claim -> ResearchQuestionOverviewClaimResponse.builder()
                        .claimId(claim.getId())
                        .statement(claim.getStatement())
                        .claimType(claim.getClaimType())
                        .stance(claim.getStance())
                        .confidence(claim.getConfidence())
                        .rationale(claim.getRationale())
                        .conceptNames(relationsByClaimId.getOrDefault(claim.getId(), List.of()).stream()
                                .map(ClaimConceptRelation::getConceptCardId)
                                .map(conceptNamesById::get)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                        .citationIds(citationsByClaimId.getOrDefault(claim.getId(), List.of()).stream()
                                .map(ClaimCitation::getCitationId)
                                .distinct()
                                .toList())
                        .build())
                .toList();
    }

    private List<ResearchQuestionOverviewEvidenceResponse> buildSupportingEvidence(
            List<Claim> currentClaims,
            Map<Long, List<ClaimCitation>> citationsByClaimId,
            Map<Long, Citation> citationsById
    ) {
        Map<Long, Claim> claimsById = currentClaims.stream()
                .collect(Collectors.toMap(Claim::getId, claim -> claim, (left, right) -> left, LinkedHashMap::new));
        List<ResearchQuestionOverviewEvidenceResponse> evidence = new ArrayList<>();
        for (Map.Entry<Long, List<ClaimCitation>> entry : citationsByClaimId.entrySet()) {
            Claim claim = claimsById.get(entry.getKey());
            if (claim == null) {
                continue;
            }
            List<CardCitationResponse> citations = entry.getValue().stream()
                    .map(ClaimCitation::getCitationId)
                    .map(citationsById::get)
                    .filter(Objects::nonNull)
                    .map(this::toCitationResponse)
                    .toList();
            if (citations.isEmpty()) {
                continue;
            }
            evidence.add(ResearchQuestionOverviewEvidenceResponse.builder()
                    .claimId(claim.getId())
                    .claimStatement(claim.getStatement())
                    .citations(citations)
                    .build());
        }
        return evidence;
    }

    private List<String> detectConflicts(
            List<Claim> currentClaims,
            Map<Long, List<ClaimConceptRelation>> relationsByClaimId,
            Map<Long, String> conceptNamesById
    ) {
        List<String> conflicts = new ArrayList<>();
        boolean hasSupported = currentClaims.stream().anyMatch(claim -> "SUPPORTED".equals(claim.getStance().name()));
        boolean hasRefuted = currentClaims.stream().anyMatch(claim -> "REFUTED".equals(claim.getStance().name()));
        if (hasSupported && hasRefuted) {
            conflicts.add("当前问题下同时存在 SUPPORTED 与 REFUTED 的判断，建议复核结论边界与证据质量。");
        }

        Map<Long, Set<String>> relationTypesByConceptId = new LinkedHashMap<>();
        for (List<ClaimConceptRelation> relations : relationsByClaimId.values()) {
            for (ClaimConceptRelation relation : relations) {
                relationTypesByConceptId
                        .computeIfAbsent(relation.getConceptCardId(), ignored -> new LinkedHashSet<>())
                        .add(normalizeRelationType(relation.getRelationType()));
            }
        }
        for (Map.Entry<Long, Set<String>> entry : relationTypesByConceptId.entrySet()) {
            if (entry.getValue().contains("SUPPORTS") && entry.getValue().contains("CONTRADICTS")) {
                String conceptName = conceptNamesById.getOrDefault(entry.getKey(), "未命名概念");
                conflicts.add("概念 " + conceptName + " 在本问题中同时承担 SUPPORTS 与 CONTRADICTS 关系，建议检查对应 claim 是否需要拆分或 supersede。");
            }
        }
        return conflicts;
    }

    private List<String> collectRelatedConcepts(
            Map<Long, List<ClaimConceptRelation>> relationsByClaimId,
            Map<Long, String> conceptNamesById
    ) {
        LinkedHashSet<String> relatedConcepts = new LinkedHashSet<>();
        for (List<ClaimConceptRelation> relations : relationsByClaimId.values()) {
            for (ClaimConceptRelation relation : relations) {
                String conceptName = conceptNamesById.get(relation.getConceptCardId());
                if (conceptName != null && !conceptName.isBlank()) {
                    relatedConcepts.add(conceptName);
                }
            }
        }
        return List.copyOf(relatedConcepts);
    }

    private String resolveCurrentAnswer(ResearchQuestion question, List<Claim> currentClaims) {
        if (question.getCurrentAnswer() != null && !question.getCurrentAnswer().isBlank()) {
            return question.getCurrentAnswer().trim();
        }
        List<String> conclusions = currentClaims.stream()
                .filter(claim -> claim.getClaimType() == ClaimType.CONCLUSION)
                .map(Claim::getStatement)
                .limit(3)
                .toList();
        if (!conclusions.isEmpty()) {
            return String.join(" ", conclusions);
        }
        return "尚未沉淀出明确结论。";
    }

    private List<String> resolveNextSteps(ResearchQuestion question, List<String> openIssues, List<Claim> currentClaims) {
        LinkedHashSet<String> nextSteps = new LinkedHashSet<>();
        if (question.getNextStep() != null && !question.getNextStep().isBlank()) {
            nextSteps.add(question.getNextStep().trim());
        }
        if (!openIssues.isEmpty()) {
            nextSteps.add("优先围绕未解决问题补充证据，并将验证结果沉淀为新的 CONCLUSION 或 DECISION。");
        }
        if (currentClaims.isEmpty()) {
            nextSteps.add("先记录当前问题下的关键 claim、关联概念和证据，再重新生成综述。");
        }
        if (nextSteps.isEmpty()) {
            nextSteps.add("继续补充能改变当前结论置信度的证据或反例。");
        }
        return List.copyOf(nextSteps);
    }

    private String resolveSummary(List<Claim> currentClaims, List<String> openIssues, String currentAnswer) {
        if (!currentClaims.isEmpty()) {
            return "当前已沉淀 " + currentClaims.size() + " 条有效判断，其中未解决问题 " + openIssues.size()
                    + " 条。当前结论摘要：" + currentAnswer;
        }
        return "当前问题尚未生成稳定综述，建议先沉淀关键判断与证据。";
    }

    private String buildMarkdown(
            ResearchQuestion question,
            String currentAnswer,
            List<ResearchQuestionOverviewClaimResponse> keyClaims,
            List<ResearchQuestionOverviewEvidenceResponse> supportingEvidence,
            List<String> conflicts,
            List<String> openIssues,
            List<String> nextSteps,
            List<String> relatedConcepts
    ) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(question.getTitle()).append("\n\n");

        markdown.append("## 当前结论\n\n");
        markdown.append(currentAnswer).append("\n\n");

        markdown.append("## 关键判断\n\n");
        if (keyClaims.isEmpty()) {
            markdown.append("- 暂无关键判断。\n\n");
        } else {
            for (ResearchQuestionOverviewClaimResponse claim : keyClaims) {
                markdown.append("- [")
                        .append(claim.claimType())
                        .append(" / ")
                        .append(claim.stance())
                        .append(" / ")
                        .append(formatConfidence(claim.confidence()))
                        .append("] ")
                        .append(claim.statement())
                        .append("\n");
                if (claim.rationale() != null && !claim.rationale().isBlank()) {
                    markdown.append("  - rationale: ").append(claim.rationale()).append("\n");
                }
                if (claim.conceptNames() != null && !claim.conceptNames().isEmpty()) {
                    markdown.append("  - concepts: ").append(String.join(", ", claim.conceptNames())).append("\n");
                }
            }
            markdown.append("\n");
        }

        markdown.append("## 支持证据\n\n");
        if (supportingEvidence.isEmpty()) {
            markdown.append("- 暂无已关联证据。\n\n");
        } else {
            for (ResearchQuestionOverviewEvidenceResponse evidence : supportingEvidence) {
                markdown.append("- claim: ").append(evidence.claimStatement()).append("\n");
                for (CardCitationResponse citation : evidence.citations()) {
                    markdown.append("  - citation #")
                            .append(citation.id())
                            .append(": ")
                            .append(citation.title() == null || citation.title().isBlank() ? "未命名证据" : citation.title());
                    String quote = abbreviate(citation.quoteText(), 160);
                    if (quote != null) {
                        markdown.append(" | ").append(quote);
                    }
                    markdown.append("\n");
                }
            }
            markdown.append("\n");
        }

        markdown.append("## 争议与冲突\n\n");
        appendStringList(markdown, conflicts, "暂无已检测到的冲突。");

        markdown.append("## 未解决问题\n\n");
        appendStringList(markdown, openIssues, "暂无未解决问题。");

        markdown.append("## 下一步\n\n");
        appendStringList(markdown, nextSteps, "继续补充研究判断。");

        markdown.append("## 关联概念\n\n");
        appendStringList(markdown, relatedConcepts, "暂无关联概念。");
        return markdown.toString().trim();
    }

    private void appendStringList(StringBuilder markdown, List<String> items, String fallback) {
        if (items == null || items.isEmpty()) {
            markdown.append("- ").append(fallback).append("\n\n");
            return;
        }
        for (String item : items) {
            markdown.append("- ").append(item).append("\n");
        }
        markdown.append("\n");
    }

    private ResearchQuestionOverviewResponse toResponse(ResearchQuestionOverview overview, boolean generated) {
        List<ResearchQuestionOverviewClaimResponse> keyClaims = readJson(overview.getKeyClaimsJson(), KEY_CLAIMS_TYPE, List.of());
        List<String> relatedConcepts = keyClaims.stream()
                .flatMap(claim -> claim.conceptNames().stream())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ResearchQuestionOverviewResponse.builder()
                .id(overview.getId())
                .spaceId(overview.getSpaceId())
                .userId(overview.getUserId())
                .researchProjectId(overview.getResearchProjectId())
                .researchQuestionId(overview.getResearchQuestionId())
                .title(overview.getTitle())
                .summary(overview.getSummary())
                .currentAnswer(overview.getCurrentAnswer())
                .keyClaims(keyClaims)
                .supportingEvidence(readJson(overview.getSupportingEvidenceJson(), SUPPORTING_EVIDENCE_TYPE, List.of()))
                .conflicts(readJson(overview.getConflictsJson(), STRING_LIST_TYPE, List.of()))
                .openIssues(readJson(overview.getOpenIssuesJson(), STRING_LIST_TYPE, List.of()))
                .nextSteps(readJson(overview.getNextStepsJson(), STRING_LIST_TYPE, List.of()))
                .relatedConcepts(relatedConcepts)
                .markdown(overview.getMarkdown())
                .generatedFromSnapshot(readJson(
                        overview.getGeneratedFromSnapshotJson(),
                        SNAPSHOT_TYPE,
                        ResearchQuestionOverviewSnapshotResponse.builder()
                                .claimIds(List.of())
                                .conceptCardIds(List.of())
                                .citationIds(List.of())
                                .sessionSummaryIds(List.of())
                                .build()))
                .generated(generated)
                .createdAt(overview.getCreatedAt())
                .updatedAt(overview.getUpdatedAt())
                .build();
    }

    private ResearchQuestionOverviewResponse emptyResponse(ResearchQuestion question) {
        List<String> nextSteps = resolveNextSteps(question, List.of(), List.of());
        String markdown = buildMarkdown(
                question,
                resolveCurrentAnswer(question, List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                nextSteps,
                List.of()
        );
        return ResearchQuestionOverviewResponse.builder()
                .id(null)
                .spaceId(question.getSpaceId())
                .userId(question.getUserId())
                .researchProjectId(question.getResearchProjectId())
                .researchQuestionId(question.getId())
                .title(question.getTitle())
                .summary("尚未生成问题综述。")
                .currentAnswer(resolveCurrentAnswer(question, List.of()))
                .keyClaims(List.of())
                .supportingEvidence(List.of())
                .conflicts(List.of())
                .openIssues(List.of())
                .nextSteps(nextSteps)
                .relatedConcepts(List.of())
                .markdown(markdown)
                .generatedFromSnapshot(ResearchQuestionOverviewSnapshotResponse.builder()
                        .claimIds(List.of())
                        .conceptCardIds(List.of())
                        .citationIds(List.of())
                        .sessionSummaryIds(List.of())
                        .build())
                .generated(false)
                .createdAt(null)
                .updatedAt(null)
                .build();
    }

    private CardCitationResponse toCitationResponse(Citation citation) {
        return CardCitationResponse.builder()
                .id(citation.getId())
                .sourceType(citation.getSourceType())
                .sourceId(citation.getSourceId())
                .chunkId(citation.getChunkId())
                .title(citation.getTitle())
                .quoteText(citation.getQuoteText())
                .locationInfo(citation.getLocationInfo())
                .pageNo(citation.getPageNo())
                .startOffset(citation.getStartOffset())
                .endOffset(citation.getEndOffset())
                .quoteHash(citation.getQuoteHash())
                .snapshotObjectKey(citation.getSnapshotObjectKey())
                .sourceVersion(citation.getSourceVersion())
                .createdAt(citation.getCreatedAt())
                .build();
    }

    private List<Long> claimIds(List<Claim> claims) {
        return claims.stream().map(Claim::getId).toList();
    }

    private List<Long> collectConceptIds(Map<Long, List<ClaimConceptRelation>> relationsByClaimId) {
        LinkedHashSet<Long> conceptIds = new LinkedHashSet<>();
        for (List<ClaimConceptRelation> relations : relationsByClaimId.values()) {
            for (ClaimConceptRelation relation : relations) {
                conceptIds.add(relation.getConceptCardId());
            }
        }
        return List.copyOf(conceptIds);
    }

    private List<Long> collectCitationIds(Map<Long, List<ClaimCitation>> citationsByClaimId) {
        LinkedHashSet<Long> citationIds = new LinkedHashSet<>();
        for (List<ClaimCitation> citations : citationsByClaimId.values()) {
            for (ClaimCitation citation : citations) {
                citationIds.add(citation.getCitationId());
            }
        }
        return List.copyOf(citationIds);
    }

    private int claimPriority(ClaimType claimType) {
        if (claimType == ClaimType.CONCLUSION) {
            return 0;
        }
        if (claimType == ClaimType.DECISION) {
            return 1;
        }
        if (claimType == ClaimType.HYPOTHESIS) {
            return 2;
        }
        return 3;
    }

    private String normalizeRelationType(String relationType) {
        return relationType == null ? "RELATED" : relationType.trim().toUpperCase(Locale.ROOT);
    }

    private String formatConfidence(java.math.BigDecimal confidence) {
        if (confidence == null) {
            return "0";
        }
        return confidence.stripTrailingZeros().toPlainString();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 3) + "...";
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to serialize research question overview");
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to parse research question overview");
        }
    }

    private record OverviewDocument(
            String summary,
            String currentAnswer,
            List<ResearchQuestionOverviewClaimResponse> keyClaims,
            List<ResearchQuestionOverviewEvidenceResponse> supportingEvidence,
            List<String> conflicts,
            List<String> openIssues,
            List<String> nextSteps,
            List<String> relatedConcepts,
            ResearchQuestionOverviewSnapshotResponse snapshot,
            String markdown
    ) {
    }
}
