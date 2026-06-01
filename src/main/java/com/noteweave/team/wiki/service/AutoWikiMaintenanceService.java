package com.noteweave.team.wiki.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.citation.model.Citation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.ConceptRelation;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.card.repository.ConceptRelationRepository;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.source.model.Source;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.service.TaskCreateCommand;
import com.noteweave.task.service.TaskService;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentChunk;
import com.noteweave.team.wiki.model.WikiIndexStatus;
import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageCitation;
import com.noteweave.team.wiki.model.WikiPageStatus;
import com.noteweave.team.wiki.model.WikiPageVersion;
import com.noteweave.team.wiki.repository.WikiPageCitationRepository;
import com.noteweave.team.wiki.repository.WikiPageRepository;
import com.noteweave.team.wiki.repository.WikiPageVersionRepository;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoWikiMaintenanceService {

    private static final String WIKI_PAGE_TARGET_TYPE = "WIKI_PAGE";
    private static final int MAX_DOC_CONTENT_CHARS = 12_000;
    private static final int MAX_SECTION_CHARS = 2_400;

    private final WikiPageRepository wikiPageRepository;
    private final WikiPageVersionRepository wikiPageVersionRepository;
    private final WikiPageCitationRepository wikiPageCitationRepository;
    private final CitationRepository citationRepository;
    private final TaskService taskService;
    private final WikiGraphSyncService wikiGraphSyncService;
    private final ObjectProvider<WikiIndexService> wikiIndexServiceProvider;
    private final ConceptCardRepository conceptCardRepository;
    private final ConceptRelationRepository conceptRelationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public WikiPage syncDocumentWiki(Long userId, Document document, String parsedText, List<DocumentChunk> chunks) {
        if (document == null || document.getId() == null || document.getDeletedAt() != null || document.getActiveIndexVersion() <= 0) {
            return null;
        }
        String fingerprint = sha256("DOCUMENT|" + document.getId() + "|" + document.getActiveIndexVersion() + "|" + document.getContentHash());
        WikiPage existing = wikiPageRepository
                .findAutoDocumentPageForUpdate(document.getSpaceId(), document.getId())
                .orElse(null);
        if (existing != null && Objects.equals(existing.getSourceFingerprint(), fingerprint)) {
            return existing;
        }

        String title = titleForDocument(document);
        String content = buildDocumentWikiContent(document, parsedText, chunks);
        WikiPage page = existing == null ? new WikiPage() : existing;
        if (existing == null) {
            page.setSpaceId(document.getSpaceId());
            page.setCreatedBy(userId);
            page.setSourceDocumentId(document.getId());
            page.setAutoMaintained(true);
        }
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(WikiPageStatus.PUBLISHED);
        page.setIndexStatus(WikiIndexStatus.PENDING);
        page.setSourceDocumentId(document.getId());
        page.setSourceFingerprint(fingerprint);
        page.setUpdatedBy(userId);
        page = wikiPageRepository.save(page);

        WikiPageVersion version = createVersion(page, userId, existing == null ? "auto wiki created from document" : "auto wiki refreshed from document");
        page.setPublishedVersionId(version.getId());
        page = wikiPageRepository.save(page);
        replaceDocumentCitations(page, version, document, chunks);
        createIndexTask(userId, page, version, "AUTO_WIKI_DOCUMENT");
        wikiGraphSyncService.rebuildSpaceGraph(page.getSpaceId());
        return page;
    }

    @Transactional
    public WikiPage syncPersonalSourceWiki(
            Long userId,
            ResearchProject project,
            Source source,
            ArticleCard articleCard,
            Map<String, Object> output
    ) {
        if (project == null || source == null || articleCard == null || source.getDeletedAt() != null) {
            return null;
        }
        List<ConceptCard> concepts = conceptCardRepository
                .findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(project.getId(), project.getSpaceId());
        List<ConceptRelation> relations = conceptRelationRepository.findByResearchProjectId(project.getId());
        String fingerprint = sha256("SOURCE|" + source.getId() + "|" + source.getContentHash() + "|"
                + articleCard.getUpdatedAt() + "|" + fingerprintConcepts(concepts) + "|" + fingerprintRelations(relations));

        WikiPage existing = wikiPageRepository
                .findAutoPersonalSourcePageForUpdate(project.getSpaceId(), source.getId())
                .orElse(null);
        if (existing != null && Objects.equals(existing.getSourceFingerprint(), fingerprint)) {
            return existing;
        }

        String content = buildPersonalSourceWikiContent(project, source, articleCard, concepts, relations, output);
        WikiPage page = existing == null ? new WikiPage() : existing;
        if (existing == null) {
            page.setSpaceId(project.getSpaceId());
            page.setCreatedBy(userId);
            page.setSourcePersonalSourceId(source.getId());
            page.setAutoMaintained(true);
        }
        page.setTitle(titleForSource(articleCard, source));
        page.setContent(content);
        page.setStatus(WikiPageStatus.PUBLISHED);
        page.setIndexStatus(WikiIndexStatus.PENDING);
        page.setSourcePersonalSourceId(source.getId());
        page.setSourceFingerprint(fingerprint);
        page.setUpdatedBy(userId);
        page = wikiPageRepository.save(page);

        WikiPageVersion version = createVersion(page, userId, existing == null ? "auto wiki created from personal source" : "auto wiki refreshed from personal source");
        page.setPublishedVersionId(version.getId());
        page = wikiPageRepository.save(page);
        replaceSourceCitations(page, version, source);
        createIndexTask(userId, page, version, "AUTO_WIKI_PERSONAL_SOURCE");
        wikiGraphSyncService.rebuildSpaceGraph(page.getSpaceId());
        if (output != null) {
            output.put("autoWikiPageId", page.getId());
            output.put("autoWikiVersionId", version.getId());
        }
        return page;
    }

    @Transactional
    public void archiveDocumentWiki(Long userId, Document document) {
        if (document == null || document.getId() == null || document.getSpaceId() == null) {
            return;
        }
        WikiPage page = wikiPageRepository
                .findAutoDocumentPageForUpdate(document.getSpaceId(), document.getId())
                .orElse(null);
        archiveAutoPage(userId, page, "source document deleted");
    }

    @Transactional
    public void archivePersonalSourceWiki(Long userId, Source source) {
        if (source == null || source.getId() == null || source.getSpaceId() == null) {
            return;
        }
        WikiPage page = wikiPageRepository
                .findAutoPersonalSourcePageForUpdate(source.getSpaceId(), source.getId())
                .orElse(null);
        archiveAutoPage(userId, page, "personal source deleted");
    }

    private WikiPageVersion createVersion(WikiPage page, Long userId, String changeNote) {
        int nextVersionNo = wikiPageVersionRepository.findMaxVersionNo(page.getId()) + 1;
        WikiPageVersion version = new WikiPageVersion();
        version.setWikiPageId(page.getId());
        version.setVersionNo(nextVersionNo);
        version.setTitle(page.getTitle());
        version.setContent(page.getContent());
        version.setChangeNote(changeNote);
        version.setCreatedBy(userId);
        return wikiPageVersionRepository.save(version);
    }

    private void createIndexTask(Long userId, WikiPage page, WikiPageVersion version, String reason) {
        TaskResponse task = taskService.createTask(TaskCreateCommand.builder()
                .userId(userId)
                .spaceId(page.getSpaceId())
                .taskType(TaskType.WIKI_INDEX)
                .targetType(WIKI_PAGE_TARGET_TYPE)
                .targetId(page.getId())
                .idempotencyKey("WIKI_INDEX:" + page.getId() + ":" + version.getId())
                .input(Map.of(
                        "wikiPageId", page.getId(),
                        "publishedVersionId", version.getId(),
                        "spaceId", page.getSpaceId(),
                        "reason", reason
                ))
                .build());
        if (task.getId() == null) {
            throw new BusinessException(ErrorCode.WIKI_PUBLISH_FAILED, "failed to create auto wiki index task");
        }
    }

    private void replaceDocumentCitations(WikiPage page, WikiPageVersion version, Document document, List<DocumentChunk> chunks) {
        List<WikiPageCitation> previous = wikiPageCitationRepository.findByWikiPageIdOrderByIdAsc(page.getId());
        if (!previous.isEmpty()) {
            wikiPageCitationRepository.deleteAll(previous);
        }
        for (DocumentChunk chunk : firstChunks(chunks, 8)) {
            Citation citation = citationRepository.findBySpaceIdAndSourceTypeAndSourceIdAndChunkId(
                            page.getSpaceId(),
                            "DOCUMENT",
                            document.getId(),
                            chunk.getId()
                    )
                    .orElseGet(Citation::new);
            citation.setSpaceId(page.getSpaceId());
            citation.setSourceType("DOCUMENT");
            citation.setSourceId(document.getId());
            citation.setChunkId(chunk.getId());
            citation.setPageNo(chunk.getPageNo() == null ? 1 : chunk.getPageNo());
            citation.setStartOffset(chunk.getSourceStart());
            citation.setEndOffset(chunk.getSourceEnd());
            citation.setTitle(document.getTitle());
            citation.setQuoteText(clip(chunk.getContent(), 900));
            citation.setQuoteHash(sha256(chunk.getContent()));
            citation.setLocationInfo("chunk " + chunk.getChunkIndex());
            citation.setSourceVersion(String.valueOf(document.getActiveIndexVersion()));
            citation = citationRepository.save(citation);

            WikiPageCitation relation = new WikiPageCitation();
            relation.setWikiPageId(page.getId());
            relation.setWikiPageVersionId(version.getId());
            relation.setCitationId(citation.getId());
            relation.setRelationType("EVIDENCE");
            wikiPageCitationRepository.save(relation);
        }
    }

    private void replaceSourceCitations(WikiPage page, WikiPageVersion version, Source source) {
        List<WikiPageCitation> previous = wikiPageCitationRepository.findByWikiPageIdOrderByIdAsc(page.getId());
        if (!previous.isEmpty()) {
            wikiPageCitationRepository.deleteAll(previous);
        }
        List<Citation> citations = citationRepository
                .findBySpaceIdAndSourceTypeAndSourceIdOrderByIdAsc(page.getSpaceId(), "SOURCE", source.getId())
                .stream()
                .limit(8)
                .toList();
        for (Citation citation : citations) {
            WikiPageCitation relation = new WikiPageCitation();
            relation.setWikiPageId(page.getId());
            relation.setWikiPageVersionId(version.getId());
            relation.setCitationId(citation.getId());
            relation.setRelationType("EVIDENCE");
            wikiPageCitationRepository.save(relation);
        }
    }

    private void archiveAutoPage(Long userId, WikiPage page, String reason) {
        if (page == null || page.getDeletedAt() != null || page.getStatus() == WikiPageStatus.ARCHIVED || !page.isAutoMaintained()) {
            return;
        }
        page.setStatus(WikiPageStatus.ARCHIVED);
        page.setDeletedAt(LocalDateTime.now());
        page.setDeletedBy(userId);
        page.setUpdatedBy(userId);
        page.setIndexStatus(WikiIndexStatus.FAILED);
        page.setSourceFingerprint(sha256(reason + "|" + page.getId() + "|" + page.getUpdatedAt()));
        wikiPageRepository.save(page);
        wikiGraphSyncService.rebuildSpaceGraph(page.getSpaceId());
        try {
            wikiIndexServiceProvider.ifAvailable(service -> service.remove(page.getId()));
        } catch (Exception ex) {
            log.warn("Auto wiki index cleanup failed for archived page {}", page.getId(), ex);
        }
    }

    private String buildDocumentWikiContent(Document document, String parsedText, List<DocumentChunk> chunks) {
        List<DocumentChunk> safeChunks = chunks == null ? List.of() : chunks;
        List<String> sectionTitles = safeChunks.stream()
                .map(DocumentChunk::getSectionTitle)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(6)
                .toList();
        List<String> wikiLinks = sectionTitles.stream().map(this::wikiLink).toList();
        String body = clip(firstMeaningfulText(parsedText, safeChunks), MAX_DOC_CONTENT_CHARS);
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(titleForDocument(document)).append("\n\n");
        builder.append("> Auto Wiki: this page is maintained from document `").append(document.getId()).append("`. Raw chunks remain the fact source.\n\n");
        builder.append("## Overview\n\n");
        builder.append(summarizeText(body, document.getTitle())).append("\n\n");
        builder.append("## Key Topics\n\n");
        if (wikiLinks.isEmpty()) {
            builder.append("- [[Document Evidence]]\n");
        } else {
            wikiLinks.forEach(link -> builder.append("- ").append(link).append("\n"));
        }
        builder.append("\n## Source Notes\n\n");
        for (DocumentChunk chunk : firstChunks(safeChunks, 5)) {
            String label = chunk.getSectionTitle() == null || chunk.getSectionTitle().isBlank()
                    ? "Chunk " + (chunk.getChunkIndex() + 1)
                    : chunk.getSectionTitle();
            builder.append("### ").append(normalizeHeading(label)).append("\n\n");
            builder.append(clip(chunk.getContent(), MAX_SECTION_CHARS)).append("\n\n");
        }
        builder.append("## Maintenance\n\n");
        builder.append("- Source document: `").append(document.getId()).append("`\n");
        builder.append("- Knowledge base: `").append(document.getKnowledgeBaseId()).append("`\n");
        builder.append("- Index version: `").append(document.getActiveIndexVersion()).append("`\n");
        builder.append("- Content hash: `").append(nullToDash(document.getContentHash())).append("`\n");
        return builder.toString().trim();
    }

    private String buildPersonalSourceWikiContent(
            ResearchProject project,
            Source source,
            ArticleCard articleCard,
            List<ConceptCard> concepts,
            List<ConceptRelation> relations,
            Map<String, Object> output
    ) {
        List<String> keyPoints = readStringList(articleCard.getKeyPointsJson());
        Set<Long> conceptIds = new LinkedHashSet<>();
        if (output != null && output.get("conceptCount") != null) {
            log.debug("auto wiki source {} conceptCount={}", source.getId(), output.get("conceptCount"));
        }
        List<ConceptCard> relatedConcepts = concepts.stream()
                .filter(concept -> concept.getEvidenceQuotesJson() != null && concept.getEvidenceQuotesJson().contains("\"sourceId\":" + source.getId()))
                .limit(8)
                .toList();
        relatedConcepts.forEach(concept -> conceptIds.add(concept.getId()));

        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(titleForSource(articleCard, source)).append("\n\n");
        builder.append("> Auto Wiki: this page is maintained from personal source `").append(source.getId()).append("` in project `").append(project.getId()).append("`.\n\n");
        builder.append("## Overview\n\n");
        builder.append(blankToFallback(articleCard.getSummary(), "This source has been compiled into article and concept cards.")).append("\n\n");
        if (!keyPoints.isEmpty()) {
            builder.append("## Key Points\n\n");
            keyPoints.forEach(point -> builder.append("- ").append(point).append("\n"));
            builder.append("\n");
        }
        builder.append("## Concepts\n\n");
        if (relatedConcepts.isEmpty()) {
            builder.append("- [[").append(project.getTitle()).append("]]\n\n");
        } else {
            for (ConceptCard concept : relatedConcepts) {
                builder.append("- ").append(wikiLink(concept.getName()));
                if (concept.getDefinition() != null && !concept.getDefinition().isBlank()) {
                    builder.append(": ").append(clip(concept.getDefinition(), 220));
                }
                builder.append("\n");
            }
            builder.append("\n");
        }
        List<ConceptRelation> relatedRelations = relations.stream()
                .filter(relation -> conceptIds.contains(relation.getSourceConceptId()) || conceptIds.contains(relation.getTargetConceptId()))
                .limit(8)
                .toList();
        if (!relatedRelations.isEmpty()) {
            Map<Long, String> namesById = new HashMap<>();
            relatedConcepts.forEach(concept -> namesById.put(concept.getId(), concept.getName()));
            builder.append("## Relations\n\n");
            for (ConceptRelation relation : relatedRelations) {
                builder.append("- ")
                        .append(wikiLink(namesById.getOrDefault(relation.getSourceConceptId(), "Concept " + relation.getSourceConceptId())))
                        .append(" ")
                        .append(relation.getRelationType())
                        .append(" ")
                        .append(wikiLink(namesById.getOrDefault(relation.getTargetConceptId(), "Concept " + relation.getTargetConceptId())));
                if (relation.getDescription() != null && !relation.getDescription().isBlank()) {
                    builder.append(": ").append(clip(relation.getDescription(), 220));
                }
                builder.append("\n");
            }
            builder.append("\n");
        }
        builder.append("## Evidence\n\n");
        List<Map<String, Object>> evidence = readObjectList(articleCard.getEvidenceQuotesJson());
        if (evidence.isEmpty()) {
            builder.append("- Source citations are available on the generated article and concept cards.\n");
        } else {
            for (Map<String, Object> item : evidence.stream().limit(5).toList()) {
                Object quote = item.get("quote");
                if (quote != null && !quote.toString().isBlank()) {
                    builder.append("- ").append(clip(quote.toString(), 360)).append("\n");
                }
            }
        }
        builder.append("\n## Maintenance\n\n");
        builder.append("- Research project: `").append(project.getId()).append("`\n");
        builder.append("- Source: `").append(source.getId()).append("`\n");
        builder.append("- Source type: `").append(source.getSourceType()).append("`\n");
        builder.append("- Article card: `").append(articleCard.getId()).append("`\n");
        if (source.getUpdatedAt() != null) {
            builder.append("- Last source update: `").append(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(source.getUpdatedAt())).append("`\n");
        }
        return builder.toString().trim();
    }

    private String firstMeaningfulText(String parsedText, List<DocumentChunk> chunks) {
        if (parsedText != null && !parsedText.isBlank()) {
            return parsedText;
        }
        return chunks.stream()
                .map(DocumentChunk::getContent)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String summarizeText(String text, String fallbackTitle) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "This page tracks the indexed document " + fallbackTitle + ".";
        }
        int firstStop = firstStop(normalized);
        if (firstStop > 80) {
            return normalized.substring(0, firstStop + 1);
        }
        return clip(normalized, 520);
    }

    private int firstStop(String value) {
        int best = -1;
        for (String stop : List.of("。", ".", "!", "?", "；", ";")) {
            int index = value.indexOf(stop);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private List<DocumentChunk> firstChunks(List<DocumentChunk> chunks, int limit) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .filter(chunk -> chunk.getContent() != null && !chunk.getContent().isBlank())
                .sorted(java.util.Comparator.comparingInt(DocumentChunk::getChunkIndex))
                .limit(limit)
                .toList();
    }

    private String titleForDocument(Document document) {
        return "Wiki: " + normalizeTitle(document.getTitle(), "Document " + document.getId());
    }

    private String titleForSource(ArticleCard articleCard, Source source) {
        String title = articleCard == null ? null : articleCard.getTitle();
        return "Wiki: " + normalizeTitle(title, source == null ? "Personal Source" : source.getTitle());
    }

    private String normalizeTitle(String value, String fallback) {
        String title = value == null || value.isBlank() ? fallback : value.trim();
        return clip(title.replaceAll("[\\r\\n]+", " "), 120);
    }

    private String normalizeHeading(String value) {
        return clip((value == null || value.isBlank() ? "Section" : value.trim()).replaceAll("[\\r\\n]+", " "), 120);
    }

    private String wikiLink(String value) {
        return "[[" + normalizeHeading(value).replace("[", "").replace("]", "") + "]]";
    }

    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String fingerprintConcepts(List<ConceptCard> concepts) {
        StringBuilder builder = new StringBuilder();
        concepts.stream()
                .sorted(java.util.Comparator.comparing(ConceptCard::getId, java.util.Comparator.nullsLast(Long::compareTo)))
                .forEach(concept -> builder
                        .append(concept.getId()).append(':')
                        .append(concept.getUpdatedAt()).append(':')
                        .append(nullToDash(concept.getName())).append(':')
                        .append(nullToDash(concept.getDefinition()))
                        .append('|'));
        return sha256(builder.toString());
    }

    private String fingerprintRelations(List<ConceptRelation> relations) {
        StringBuilder builder = new StringBuilder();
        relations.stream()
                .sorted(java.util.Comparator.comparing(ConceptRelation::getId, java.util.Comparator.nullsLast(Long::compareTo)))
                .forEach(relation -> builder
                        .append(relation.getId()).append(':')
                        .append(relation.getUpdatedAt()).append(':')
                        .append(relation.getSourceConceptId()).append("->")
                        .append(relation.getTargetConceptId()).append(':')
                        .append(nullToDash(relation.getRelationType())).append(':')
                        .append(nullToDash(relation.getDescription()))
                        .append('|'));
        return sha256(builder.toString());
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Map<String, Object>> readObjectList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String clip(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength)).trim();
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash auto wiki content", ex);
        }
    }
}
