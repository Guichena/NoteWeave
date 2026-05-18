package com.noteweave.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.admin.dto.AuditLogQuery;
import com.noteweave.admin.dto.AuditLogResponse;
import com.noteweave.admin.model.AuditAction;
import com.noteweave.admin.model.AuditLog;
import com.noteweave.admin.repository.AuditLogRepository;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.api.RequestIdHolder;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "operatorId");

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(
            Long operatorId,
            Long spaceId,
            AuditAction action,
            String targetType,
            Long targetId,
            Object beforeState,
            Object afterState
    ) {
        AuditLog auditLog = new AuditLog();
        auditLog.setOperatorId(operatorId);
        auditLog.setSpaceId(spaceId);
        auditLog.setAction(action);
        auditLog.setTargetType(normalize(targetType));
        auditLog.setTargetId(targetId);
        auditLog.setRequestId(RequestIdHolder.get());
        RequestInfo requestInfo = resolveRequestInfo();
        auditLog.setIpAddress(requestInfo.ipAddress());
        auditLog.setUserAgent(requestInfo.userAgent());
        auditLog.setBeforeJson(writeJson(beforeState));
        auditLog.setAfterJson(writeJson(afterState));
        auditLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(AuditLogQuery query) {
        Pageable pageable = AdminPageSupport.buildPageable(
                query.getPage(),
                query.getPageSize(),
                query.getSort(),
                ALLOWED_SORT_FIELDS,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<AuditLog> page = auditLogRepository.findAll(buildSpecification(query), pageable);
        return PageResponse.<AuditLogResponse>builder()
                .items(page.getContent().stream().map(this::toResponse).toList())
                .page(pageable.getPageNumber() + 1)
                .pageSize(pageable.getPageSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .sort(AdminPageSupport.toSortExpression(pageable.getSort()))
                .filters(Map.of())
                .build();
    }

    private Specification<AuditLog> buildSpecification(AuditLogQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.getAction() != null) {
                predicates.add(criteriaBuilder.equal(root.get("action"), query.getAction()));
            }
            if (query.getOperatorId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("operatorId"), query.getOperatorId()));
            }
            if (query.getTargetId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("targetId"), query.getTargetId()));
            }
            if (query.getTargetType() != null && !query.getTargetType().isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("targetType"), query.getTargetType().trim()));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private AuditLogResponse toResponse(AuditLog auditLog) {
        return AuditLogResponse.builder()
                .id(auditLog.getId())
                .operatorId(auditLog.getOperatorId())
                .spaceId(auditLog.getSpaceId())
                .action(auditLog.getAction())
                .targetType(auditLog.getTargetType())
                .targetId(auditLog.getTargetId())
                .requestId(auditLog.getRequestId())
                .ipAddress(auditLog.getIpAddress())
                .userAgent(auditLog.getUserAgent())
                .before(readJson(auditLog.getBeforeJson()))
                .after(readJson(auditLog.getAfterJson()))
                .createdAt(auditLog.getCreatedAt())
                .build();
    }

    private RequestInfo resolveRequestInfo() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return new RequestInfo(null, null);
        }
        HttpServletRequest request = servletRequestAttributes.getRequest();
        return new RequestInfo(normalize(request.getRemoteAddr()), normalize(request.getHeader("User-Agent")));
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize audit payload", ex);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize audit payload", ex);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record RequestInfo(String ipAddress, String userAgent) {
    }
}
