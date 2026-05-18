package com.noteweave.admin.service;

import com.noteweave.admin.dto.AdminUserQuery;
import com.noteweave.admin.dto.AdminUserResponse;
import com.noteweave.admin.model.AuditAction;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.user.model.User;
import com.noteweave.user.model.UserStatus;
import com.noteweave.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
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

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "updatedAt", "lastLoginAt");

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> searchUsers(AdminUserQuery query) {
        Pageable pageable = AdminPageSupport.buildPageable(
                query.getPage(),
                query.getPageSize(),
                query.getSort(),
                ALLOWED_SORT_FIELDS,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<User> page = userRepository.findAll(buildSpecification(query), pageable);
        return PageResponse.<AdminUserResponse>builder()
                .items(page.getContent().stream().map(this::toResponse).toList())
                .page(pageable.getPageNumber() + 1)
                .pageSize(pageable.getPageSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .sort(AdminPageSupport.toSortExpression(pageable.getSort()))
                .filters(Map.of())
                .build();
    }

    @Transactional
    public AdminUserResponse disableUser(Long operatorId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        AdminUserResponse before = toResponse(user);
        if (user.getStatus() != UserStatus.DISABLED) {
            user.setStatus(UserStatus.DISABLED);
            user.setDisabledAt(LocalDateTime.now());
            user = userRepository.save(user);
        }
        AdminUserResponse after = toResponse(user);
        auditLogService.record(operatorId, null, AuditAction.USER_DISABLE, "USER", userId, before, after);
        return after;
    }

    @Transactional
    public AdminUserResponse enableUser(Long operatorId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        AdminUserResponse before = toResponse(user);
        if (user.getStatus() != UserStatus.ACTIVE) {
            user.setStatus(UserStatus.ACTIVE);
            user.setDisabledAt(null);
            user = userRepository.save(user);
        }
        AdminUserResponse after = toResponse(user);
        auditLogService.record(operatorId, null, AuditAction.USER_ENABLE, "USER", userId, before, after);
        return after;
    }

    private Specification<User> buildSpecification(AdminUserQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), query.getStatus()));
            }
            if (query.getSystemRole() != null) {
                predicates.add(criteriaBuilder.equal(root.get("systemRole"), query.getSystemRole()));
            }
            if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
                String keyword = "%" + query.getKeyword().trim().toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("username")), keyword),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), keyword),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("displayName")), keyword)
                ));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private AdminUserResponse toResponse(User user) {
        return AdminUserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .systemRole(user.getSystemRole())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .disabledAt(user.getDisabledAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
