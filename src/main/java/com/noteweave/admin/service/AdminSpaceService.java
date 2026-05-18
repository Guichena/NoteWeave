package com.noteweave.admin.service;

import com.noteweave.admin.dto.AdminSpaceDetailResponse;
import com.noteweave.admin.dto.AdminSpaceQuery;
import com.noteweave.admin.dto.AdminSpaceResponse;
import com.noteweave.admin.model.AuditAction;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.repository.SpaceRepository;
import com.noteweave.user.model.User;
import com.noteweave.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSpaceService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "name", "createdAt", "updatedAt");

    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PageResponse<AdminSpaceResponse> searchSpaces(AdminSpaceQuery query) {
        Pageable pageable = AdminPageSupport.buildPageable(
                query.getPage(),
                query.getPageSize(),
                query.getSort(),
                ALLOWED_SORT_FIELDS,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<Space> page = spaceRepository.findAll(buildSpecification(query), pageable);
        return PageResponse.<AdminSpaceResponse>builder()
                .items(page.getContent().stream().map(this::toResponse).toList())
                .page(pageable.getPageNumber() + 1)
                .pageSize(pageable.getPageSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .sort(AdminPageSupport.toSortExpression(pageable.getSort()))
                .filters(Map.of())
                .build();
    }

    @Transactional(readOnly = true)
    public AdminSpaceDetailResponse getSpace(Long operatorId, Long spaceId) {
        Space space = spaceRepository.findById(spaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
        return AdminSpaceDetailResponse.builder()
                .space(toResponse(space))
                .knowledgeBaseIds(queryIds("select id from knowledge_base where space_id = ? order by id desc limit 20", spaceId))
                .documentIds(queryIds("select id from document where space_id = ? order by id desc limit 20", spaceId))
                .artifactIds(queryIds("select id from artifact where space_id = ? order by id desc limit 20", spaceId))
                .recentTaskIds(queryIds("select id from task where space_id = ? order by id desc limit 20", spaceId))
                .build();
    }

    @Transactional
    public void archiveSpace(Long operatorId, Long spaceId) {
        Space space = spaceRepository.findById(spaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
        AdminSpaceResponse before = toResponse(space);
        if (space.getStatus() != SpaceStatus.ARCHIVED) {
            space.setStatus(SpaceStatus.ARCHIVED);
            spaceRepository.save(space);
        }
        auditLogService.record(operatorId, spaceId, AuditAction.SPACE_ARCHIVE, "SPACE", spaceId, before, toResponse(space));
    }

    private Specification<Space> buildSpecification(AdminSpaceQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), query.getStatus()));
            }
            if (query.getType() != null) {
                predicates.add(criteriaBuilder.equal(root.get("type"), query.getType()));
            }
            if (query.getOwnerId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("ownerId"), query.getOwnerId()));
            }
            if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
                String keyword = "%" + query.getKeyword().trim().toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), keyword),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("description")), keyword)
                ));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private AdminSpaceResponse toResponse(Space space) {
        User owner = userRepository.findById(space.getOwnerId()).orElse(null);
        Long spaceId = space.getId();
        return AdminSpaceResponse.builder()
                .id(spaceId)
                .name(space.getName())
                .description(space.getDescription())
                .type(space.getType())
                .ownerId(space.getOwnerId())
                .ownerUsername(owner == null ? null : owner.getUsername())
                .status(space.getStatus())
                .knowledgeBaseCount(count("select count(*) from knowledge_base where space_id = ?", spaceId))
                .documentCount(count("select count(*) from document where space_id = ? and status <> 'DELETED' and deleted_at is null", spaceId))
                .artifactCount(count("select count(*) from artifact where space_id = ? and deleted_at is null", spaceId))
                .taskCount(count("select count(*) from task where space_id = ?", spaceId))
                .storageBytes(sum("select coalesce(sum(size), 0) from file_object where space_id = ?", spaceId))
                .createdAt(space.getCreatedAt())
                .updatedAt(space.getUpdatedAt())
                .build();
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private long sum(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private List<Long> queryIds(String sql, Long spaceId) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong(1), spaceId);
    }
}
