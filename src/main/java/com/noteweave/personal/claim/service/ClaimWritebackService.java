package com.noteweave.personal.claim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.citation.dto.CitationResponse;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.dto.LlmCallContext;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.ObservedLlmGateway;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.PersonalCardStatus;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimCitation;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import com.noteweave.personal.claim.repository.ClaimCitationRepository;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.service.ResearchQuestionService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClaimWritebackService {

    private final ClaimWritebackStrategy claimWritebackStrategy;
    private final ResearchQuestionService researchQuestionService;
    private final ClaimRepository claimRepository;
    private final ClaimConceptRelationRepository claimConceptRelationRepository;
    private final ClaimCitationRepository claimCitationRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final ObservedLlmGateway observedLlmGateway;
    private final ObjectMapper objectMapper;
    private final ClaimIndexService claimIndexService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAfterRound(
            ChatSession session,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            List<CitationResponse> citations
    ) {
        if (!claimWritebackStrategy.shouldWrite(session, userMessage, assistantMessage)) {
            return;
        }
        ResearchQuestion question = researchQuestionService.getRequiredQuestion(session.getUserId(), session.getResearchQuestionId());
        List<ClaimCandidate> candidates = extractCandidates(question, userMessage, assistantMessage, citations);
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, ConceptCard> conceptCardsByNormalizedName = loadConceptCards(question.getResearchProjectId(), candidates);
        Map<Long, CitationResponse> citationsById = new LinkedHashMap<>();
        for (CitationResponse citation : citations == null ? List.<CitationResponse>of() : citations) {
            if (citation != null && citation.getId() != null) {
                citationsById.put(citation.getId(), citation);
            }
        }
        for (ClaimCandidate candidate : candidates) {
            String writebackKey = buildWritebackKey(question.getId(), userMessage.getId(), assistantMessage.getId(), candidate);
            if (claimRepository.existsByWritebackKeyAndDeletedAtIsNull(writebackKey)) {
                continue;
            }
            Claim claim = new Claim();
            claim.setSpaceId(question.getSpaceId());
            claim.setUserId(question.getUserId());
            claim.setResearchProjectId(question.getResearchProjectId());
            claim.setResearchQuestionId(question.getId());
            claim.setStatement(candidate.statement());
            claim.setClaimType(candidate.claimType());
            claim.setStance(candidate.stance());
            claim.setConfidence(candidate.confidence());
            claim.setRationale(candidate.rationale());
            claim.setCardStatus(PersonalCardStatus.PENDING_REVIEW);
            claim.setOriginSessionId(session.getId());
            claim.setOriginUserMessageId(userMessage.getId());
            claim.setOriginAssistantMessageId(assistantMessage.getId());
            claim.setWritebackKey(writebackKey);
            Claim saved = claimRepository.save(claim);
            saveConceptLinks(saved.getId(), candidate.conceptNames(), conceptCardsByNormalizedName);
            saveCitations(saved.getId(), candidate.citationIds(), citationsById);
            claimIndexService.syncClaim(saved);
        }
    }

    private List<ClaimCandidate> extractCandidates(
            ResearchQuestion question,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            List<CitationResponse> citations
    ) {
        LlmResponse response = observedLlmGateway.chat(
                LlmCallContext.builder()
                        .userId(question.getUserId())
                        .spaceId(question.getSpaceId())
                        .sessionId(assistantMessage.getSessionId())
                        .messageId(assistantMessage.getId())
                        .scene("CLAIM_WRITEBACK")
                        .messages(List.of(new LlmMessage("user", buildPrompt(question, userMessage, assistantMessage, citations))))
                        .build(),
                LlmOptions.builder().temperature(0.1d).maxTokens(1200).build()
        ).response();
        return parseCandidates(response.content());
    }

    private String buildPrompt(
            ResearchQuestion question,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            List<CitationResponse> citations
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You extract high-value claim candidates from a research chat round.\n");
        prompt.append("Return strict JSON only. Schema:\n");
        prompt.append("{\"claims\":[{\"statement\":\"...\",\"claimType\":\"CONCLUSION|HYPOTHESIS|OPEN_ISSUE|DECISION\",\"stance\":\"SUPPORTED|REFUTED|UNCERTAIN\",\"confidence\":0.0,\"rationale\":\"...\",\"conceptNames\":[\"...\"],\"citationIds\":[1]}]}\n");
        prompt.append("Rules:\n");
        prompt.append("1. Only extract durable claims that belong to the research question.\n");
        prompt.append("2. Skip greetings, temporary reasoning steps, and unsupported filler.\n");
        prompt.append("3. Prefer at most 4 strong claims.\n");
        prompt.append("4. OPEN_ISSUE must be unresolved questions. DECISION must be explicit choices.\n");
        prompt.append("5. citationIds must come only from the provided evidence ids.\n\n");
        prompt.append("Research question title: ").append(question.getTitle()).append("\n");
        if (question.getCurrentHypothesis() != null && !question.getCurrentHypothesis().isBlank()) {
            prompt.append("Current hypothesis: ").append(question.getCurrentHypothesis()).append("\n");
        }
        prompt.append("User message:\n").append(userMessage.getContent()).append("\n\n");
        prompt.append("Assistant answer:\n").append(assistantMessage.getContent()).append("\n\n");
        prompt.append("Available evidence ids:\n");
        if (citations == null || citations.isEmpty()) {
            prompt.append("[]\n");
        } else {
            for (CitationResponse citation : citations) {
                prompt.append("- id=").append(citation.getId())
                        .append(", title=").append(citation.getTitle() == null ? "" : citation.getTitle())
                        .append(", quote=").append(abbreviate(citation.getQuoteText(), 180))
                        .append("\n");
            }
        }
        return prompt.toString();
    }

    private List<ClaimCandidate> parseCandidates(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) {
                throw new BusinessException(ErrorCode.LLM_JSON_PARSE_FAILED, "Claim writeback response root must be an object");
            }
            List<ClaimCandidate> candidates = new ArrayList<>();
            for (JsonNode node : iterable(root.path("claims"))) {
                String statement = normalizeStatement(node.path("statement").asText(null));
                if (statement == null) {
                    continue;
                }
                ClaimType claimType = parseClaimType(node.path("claimType").asText(null));
                ClaimStance stance = parseClaimStance(node.path("stance").asText(null));
                BigDecimal confidence = normalizeConfidence(node.path("confidence").isNumber()
                        ? BigDecimal.valueOf(node.path("confidence").asDouble())
                        : null);
                String rationale = normalizeOptional(node.path("rationale").asText(null));
                List<String> conceptNames = readStringArray(node.path("conceptNames"));
                List<Long> citationIds = readLongArray(node.path("citationIds"));
                candidates.add(new ClaimCandidate(statement, claimType, stance, confidence, rationale, conceptNames, citationIds));
            }
            return dedupeCandidates(candidates).stream().limit(4).toList();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.LLM_JSON_PARSE_FAILED, "Failed to parse claim writeback json: " + ex.getMessage());
        }
    }

    private List<ClaimCandidate> dedupeCandidates(List<ClaimCandidate> candidates) {
        Map<String, ClaimCandidate> deduped = new LinkedHashMap<>();
        for (ClaimCandidate candidate : candidates) {
            String key = candidate.claimType().name() + "|" + candidate.stance().name() + "|" + normalizeName(candidate.statement());
            deduped.putIfAbsent(key, candidate);
        }
        return List.copyOf(deduped.values());
    }

    private Map<String, ConceptCard> loadConceptCards(Long researchProjectId, List<ClaimCandidate> candidates) {
        LinkedHashSet<String> normalizedNames = new LinkedHashSet<>();
        for (ClaimCandidate candidate : candidates) {
            for (String conceptName : candidate.conceptNames()) {
                String normalized = normalizeName(conceptName);
                if (!normalized.isBlank()) {
                    normalizedNames.add(normalized);
                }
            }
        }
        if (normalizedNames.isEmpty()) {
            return Map.of();
        }
        return conceptCardRepository.findByResearchProjectIdAndNormalizedNameIn(researchProjectId, normalizedNames).stream()
                .collect(LinkedHashMap::new, (map, card) -> map.put(card.getNormalizedName(), card), LinkedHashMap::putAll);
    }

    private void saveConceptLinks(Long claimId, List<String> conceptNames, Map<String, ConceptCard> conceptCardsByNormalizedName) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String conceptName : conceptNames) {
            String normalized = normalizeName(conceptName);
            if (normalized.isBlank() || !seen.add(normalized)) {
                continue;
            }
            ConceptCard card = conceptCardsByNormalizedName.get(normalized);
            if (card == null) {
                continue;
            }
            ClaimConceptRelation relation = new ClaimConceptRelation();
            relation.setClaimId(claimId);
            relation.setConceptCardId(card.getId());
            relation.setRelationType("RELATED");
            claimConceptRelationRepository.save(relation);
        }
    }

    private void saveCitations(Long claimId, List<Long> citationIds, Map<Long, CitationResponse> citationsById) {
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (Long citationId : citationIds) {
            if (citationId == null || !seen.add(citationId) || !citationsById.containsKey(citationId)) {
                continue;
            }
            ClaimCitation relation = new ClaimCitation();
            relation.setClaimId(claimId);
            relation.setCitationId(citationId);
            relation.setRelationType("EVIDENCE");
            claimCitationRepository.save(relation);
        }
    }

    private String buildWritebackKey(Long questionId, Long userMessageId, Long assistantMessageId, ClaimCandidate candidate) {
        return sha256(questionId + "|" + userMessageId + "|" + assistantMessageId + "|"
                + candidate.claimType().name() + "|" + candidate.stance().name() + "|" + normalizeName(candidate.statement()));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash claim writeback key", ex);
        }
    }

    private String normalizeName(String raw) {
        String normalized = normalizeOptional(raw);
        if (normalized == null) {
            return "";
        }
        return Normalizer.normalize(normalized, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private ClaimType parseClaimType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ClaimType.HYPOTHESIS;
        }
        try {
            return ClaimType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return ClaimType.HYPOTHESIS;
        }
    }

    private ClaimStance parseClaimStance(String raw) {
        if (raw == null || raw.isBlank()) {
            return ClaimStance.UNCERTAIN;
        }
        try {
            return ClaimStance.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return ClaimStance.UNCERTAIN;
        }
    }

    private BigDecimal normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return BigDecimal.valueOf(0.5d);
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return confidence;
    }

    private String normalizeStatement(String statement) {
        String normalized = normalizeOptional(statement);
        if (normalized == null) {
            return null;
        }
        return normalized.length() <= 2048 ? normalized : normalized.substring(0, 2048);
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = normalizeOptional(item.asText(null));
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private List<Long> readLongArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item.isIntegralNumber()) {
                values.add(item.asLong());
            }
        }
        return List.copyOf(values);
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        return node == null || !node.isArray() ? List.of() : () -> node.elements();
    }

    private String abbreviate(String text, int maxLength) {
        String normalized = normalizeOptional(text);
        if (normalized == null) {
            return "";
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength - 3) + "...";
    }

    private record ClaimCandidate(
            String statement,
            ClaimType claimType,
            ClaimStance stance,
            BigDecimal confidence,
            String rationale,
            List<String> conceptNames,
            List<Long> citationIds
    ) {
    }
}
