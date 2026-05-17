package com.noteweave.llm.service;

import com.noteweave.common.api.PageResponse;
import com.noteweave.llm.dto.LlmCallContext;
import com.noteweave.llm.dto.LlmCallLogQuery;
import com.noteweave.llm.dto.LlmCallLogResponse;
import com.noteweave.llm.dto.TokenUsage;
import com.noteweave.llm.model.LlmCallLog;
import com.noteweave.llm.repository.LlmCallLogRepository;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LlmCallLogService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "latencyMs", "totalTokens");

    private final LlmCallLogRepository llmCallLogRepository;

    @Transactional
    public Long begin(LlmCallContext context) {
        LlmCallLog log = new LlmCallLog();
        log.setUserId(context.userId());
        log.setSpaceId(context.spaceId());
        log.setSessionId(context.sessionId());
        log.setMessageId(context.messageId());
        log.setTaskId(context.taskId());
        log.setArtifactId(context.artifactId());
        log.setScene(context.scene());
        log.setProvider(context.provider());
        log.setModel(context.model());
        log.setPromptVersionId(context.promptVersionId());
        log.setPromptHash(sha256(context.messages()));
        log.setInputTokens(0);
        log.setOutputTokens(0);
        log.setTotalTokens(0);
        log.setLatencyMs(0L);
        log.setSuccess(false);
        return llmCallLogRepository.save(log).getId();
    }

    @Transactional
    public void markSuccess(Long logId, TokenUsage usage, long latencyMs) {
        LlmCallLog log = llmCallLogRepository.findById(logId).orElseThrow();
        log.setInputTokens(usage.inputTokens());
        log.setOutputTokens(usage.outputTokens());
        log.setTotalTokens(usage.totalTokens());
        log.setLatencyMs(Math.max(1L, latencyMs));
        log.setSuccess(true);
        log.setErrorCode(null);
        log.setErrorMessage(null);
        llmCallLogRepository.save(log);
    }

    @Transactional
    public void markFailure(Long logId, String errorCode, String errorMessage, long latencyMs) {
        LlmCallLog log = llmCallLogRepository.findById(logId).orElseThrow();
        log.setLatencyMs(Math.max(1L, latencyMs));
        log.setSuccess(false);
        log.setErrorCode(errorCode);
        log.setErrorMessage(trim(errorMessage, 4000));
        llmCallLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public PageResponse<LlmCallLogResponse> search(LlmCallLogQuery query) {
        Pageable pageable = buildPageable(query.getPage(), query.getPageSize(), query.getSort());
        Page<LlmCallLog> page = llmCallLogRepository.findAll(buildSpecification(query), pageable);
        return PageResponse.<LlmCallLogResponse>builder()
                .items(page.getContent().stream().map(this::toResponse).toList())
                .page(pageable.getPageNumber() + 1)
                .pageSize(pageable.getPageSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .sort(toSortExpression(pageable.getSort()))
                .filters(java.util.Map.of())
                .build();
    }

    @Transactional(readOnly = true)
    public LlmCallLogResponse get(Long logId) {
        return llmCallLogRepository.findById(logId).map(this::toResponse).orElse(null);
    }

    private Specification<LlmCallLog> buildSpecification(LlmCallLogQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.getSpaceId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("spaceId"), query.getSpaceId()));
            }
            if (query.getSessionId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("sessionId"), query.getSessionId()));
            }
            if (query.getTaskId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("taskId"), query.getTaskId()));
            }
            if (query.getArtifactId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("artifactId"), query.getArtifactId()));
            }
            if (query.getScene() != null && !query.getScene().isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("scene"), query.getScene().trim().toUpperCase()));
            }
            if (query.getSuccess() != null) {
                predicates.add(criteriaBuilder.equal(root.get("success"), query.getSuccess()));
            }
            if (query.getProvider() != null && !query.getProvider().isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("provider"), query.getProvider().trim()));
            }
            if (query.getModel() != null && !query.getModel().isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("model"), query.getModel().trim()));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Pageable buildPageable(int page, int pageSize, String sortValue) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        return PageRequest.of(safePage - 1, safePageSize, parseSort(sortValue));
    }

    private Sort parseSort(String sortValue) {
        Sort defaultSort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sortValue == null || sortValue.isBlank()) {
            return defaultSort;
        }
        String[] parts = sortValue.split(",");
        String property = parts[0].trim();
        if (!ALLOWED_SORT_FIELDS.contains(property)) {
            return defaultSort;
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, property);
    }

    private String toSortExpression(Sort sort) {
        Sort.Order order = sort.stream().findFirst().orElse(Sort.Order.desc("createdAt"));
        return order.getProperty() + "," + order.getDirection().name().toLowerCase();
    }

    private String sha256(List<?> messages) {
        try {
            String value = messages == null ? "" : String.valueOf(messages);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash llm prompt", ex);
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private LlmCallLogResponse toResponse(LlmCallLog log) {
        return LlmCallLogResponse.builder()
                .id(log.getId())
                .userId(log.getUserId())
                .spaceId(log.getSpaceId())
                .sessionId(log.getSessionId())
                .messageId(log.getMessageId())
                .taskId(log.getTaskId())
                .artifactId(log.getArtifactId())
                .scene(log.getScene())
                .provider(log.getProvider())
                .model(log.getModel())
                .promptVersionId(log.getPromptVersionId())
                .promptHash(log.getPromptHash())
                .inputTokens(log.getInputTokens())
                .outputTokens(log.getOutputTokens())
                .totalTokens(log.getTotalTokens())
                .latencyMs(log.getLatencyMs())
                .success(log.isSuccess())
                .errorCode(log.getErrorCode())
                .errorMessage(log.getErrorMessage())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
