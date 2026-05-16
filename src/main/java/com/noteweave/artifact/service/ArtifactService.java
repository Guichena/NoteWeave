package com.noteweave.artifact.service;

import com.noteweave.artifact.dto.ArtifactCitationDto;
import com.noteweave.artifact.dto.ArtifactQuery;
import com.noteweave.artifact.dto.ArtifactResponse;
import com.noteweave.artifact.dto.ArtifactSourceDto;
import com.noteweave.artifact.dto.ExportArtifactResponse;
import com.noteweave.artifact.dto.UpdateArtifactRequest;
import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactCitation;
import com.noteweave.artifact.model.ArtifactScopeType;
import com.noteweave.artifact.model.ArtifactSource;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactVersion;
import com.noteweave.artifact.repository.ArtifactCitationRepository;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.artifact.repository.ArtifactSourceRepository;
import com.noteweave.artifact.repository.ArtifactVersionRepository;
import com.noteweave.artifact.repository.SessionArtifactRepository;
import com.noteweave.chat.service.ChatSessionService;
import com.noteweave.citation.model.Citation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Sort;

@Service
@RequiredArgsConstructor
public class ArtifactService {

    private final ArtifactRepository artifactRepository;
    private final ArtifactVersionRepository artifactVersionRepository;
    private final ArtifactSourceRepository artifactSourceRepository;
    private final ArtifactCitationRepository artifactCitationRepository;
    private final SessionArtifactRepository sessionArtifactRepository;
    private final CitationRepository citationRepository;
    private final ResourceAccessService resourceAccessService;
    private final ChatSessionService chatSessionService;
    private final ArtifactStorageSupport artifactStorageSupport;

    @Transactional(readOnly = true)
    public List<ArtifactResponse> listBySpace(Long userId, Long spaceId, ArtifactQuery query) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        Specification<Artifact> specification = buildSpaceSpecification(spaceId, query);
        return artifactRepository.findAll(specification, Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ArtifactResponse> listBySession(Long userId, Long sessionId) {
        var session = chatSessionService.getRequiredActiveSession(sessionId);
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());
        return sessionArtifactRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(SessionArtifact -> artifactRepository.findByIdAndDeletedAtIsNull(SessionArtifact.getArtifactId()))
                .flatMap(java.util.Optional::stream)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ArtifactResponse get(Long userId, Long artifactId) {
        return toResponse(getRequiredReadableArtifact(userId, artifactId));
    }

    @Transactional
    public ArtifactResponse update(Long userId, Long artifactId, UpdateArtifactRequest request) {
        Artifact artifact = getRequiredWritableArtifact(userId, artifactId);
        int nextVersion = artifactVersionRepository.findMaxVersionNo(artifact.getId()) + 1;
        ArtifactVersion version = new ArtifactVersion();
        version.setArtifactId(artifact.getId());
        version.setVersionNo(nextVersion);
        version.setTaskId(null);
        version.setTitle(request.getTitle().trim());
        version.setContent(request.getContent().trim());
        version.setChangeNote(normalizeChangeNote(request.getChangeNote(), "manual update"));
        version.setCreatedBy(userId);
        artifactVersionRepository.save(version);

        artifact.setTitle(version.getTitle());
        artifact.setContent(version.getContent());
        artifact.setStatus(ArtifactStatus.READY);
        artifactRepository.save(artifact);
        return toResponse(artifact);
    }

    @Transactional
    public void archive(Long userId, Long artifactId) {
        Artifact artifact = getRequiredWritableArtifact(userId, artifactId);
        artifact.setStatus(ArtifactStatus.ARCHIVED);
        artifact.setDeletedAt(LocalDateTime.now());
        artifact.setDeletedBy(userId);
        artifactRepository.save(artifact);
    }

    @Transactional(readOnly = true)
    public ExportArtifactResponse export(Long userId, Long artifactId, String format) {
        Artifact artifact = getRequiredReadableArtifact(userId, artifactId);
        if (!"markdown".equalsIgnoreCase(format)) {
            throw new BusinessException(ErrorCode.ARTIFACT_EXPORT_UNSUPPORTED);
        }
        String title = normalizeFileName(artifact.getTitle());
        String content = normalizeMarkdown(artifact.getTitle(), artifact.getContent());
        String fileName = title + ".md";
        String objectKey = artifactStorageSupport.writeMarkdownExport(artifact.getId(), fileName, content);
        return ExportArtifactResponse.builder()
                .artifactId(artifact.getId())
                .format("markdown")
                .fileName(fileName)
                .objectKey(objectKey)
                .content(content)
                .build();
    }

    @Transactional(readOnly = true)
    public Artifact getRequiredReadableArtifact(Long userId, Long artifactId) {
        Artifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND));
        if (artifact.getDeletedAt() != null || artifact.getStatus() == ArtifactStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        if (!resourceAccessService.canViewSpace(userId, artifact.getSpaceId())) {
            throw new BusinessException(ErrorCode.ARTIFACT_ACCESS_DENIED, "No permission to access this artifact");
        }
        return artifact;
    }

    @Transactional
    public Artifact getRequiredWritableArtifact(Long userId, Long artifactId) {
        Artifact artifact = artifactRepository.findByIdForUpdate(artifactId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND));
        if (artifact.getDeletedAt() != null || artifact.getStatus() == ArtifactStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        boolean canWrite = artifact.getUserId().equals(userId) || resourceAccessService.canManageSpace(userId, artifact.getSpaceId());
        if (!canWrite) {
            throw new BusinessException(ErrorCode.ARTIFACT_ACCESS_DENIED, "No permission to modify this artifact");
        }
        return artifact;
    }

    public ArtifactResponse toResponse(Artifact artifact) {
        int latestVersionNo = artifactVersionRepository.findTopByArtifactIdOrderByVersionNoDesc(artifact.getId())
                .map(ArtifactVersion::getVersionNo)
                .orElse(0);
        List<ArtifactSourceDto> sources = artifactSourceRepository.findByArtifactIdOrderByIdAsc(artifact.getId()).stream()
                .map(source -> ArtifactSourceDto.builder()
                        .sourceType(source.getSourceType().name())
                        .sourceId(source.getSourceId())
                        .build())
                .toList();
        List<ArtifactCitationDto> citations = artifactCitationRepository.findByArtifactIdOrderByIdAsc(artifact.getId()).stream()
                .map(ArtifactCitation::getCitationId)
                .map(citationRepository::findById)
                .flatMap(java.util.Optional::stream)
                .map(this::toCitationDto)
                .toList();
        return ArtifactResponse.builder()
                .id(artifact.getId())
                .userId(artifact.getUserId())
                .spaceId(artifact.getSpaceId())
                .researchProjectId(artifact.getResearchProjectId())
                .createdFromSessionId(artifact.getCreatedFromSessionId())
                .createdFromMessageId(artifact.getCreatedFromMessageId())
                .taskId(artifact.getTaskId())
                .artifactType(artifact.getArtifactType())
                .title(artifact.getTitle())
                .content(artifact.getContent())
                .sourceScopeType(artifact.getSourceScopeType())
                .status(artifact.getStatus())
                .latestVersionNo(latestVersionNo)
                .sources(sources)
                .citations(citations)
                .createdAt(artifact.getCreatedAt())
                .updatedAt(artifact.getUpdatedAt())
                .build();
    }

    private ArtifactCitationDto toCitationDto(Citation citation) {
        return ArtifactCitationDto.builder()
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

    private String normalizeFileName(String title) {
        if (title == null || title.isBlank()) {
            return "artifact";
        }
        String sanitized = title.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.isBlank() ? "artifact" : sanitized;
    }

    private String normalizeMarkdown(String title, String content) {
        String safeContent = content == null ? "" : content.trim();
        if (safeContent.startsWith("#")) {
            return safeContent;
        }
        String safeTitle = title == null || title.isBlank() ? "Artifact" : title.trim();
        return "# " + safeTitle + "\n\n" + safeContent;
    }

    private String normalizeChangeNote(String changeNote, String fallback) {
        if (changeNote == null || changeNote.isBlank()) {
            return fallback;
        }
        return changeNote.trim();
    }

    private Specification<Artifact> buildSpaceSpecification(Long spaceId, ArtifactQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("spaceId"), spaceId));
            predicates.add(criteriaBuilder.isNull(root.get("deletedAt")));

            if (query != null) {
                if (query.getArtifactType() != null) {
                    predicates.add(criteriaBuilder.equal(root.get("artifactType"), query.getArtifactType()));
                }
                if (query.getStatus() != null) {
                    predicates.add(criteriaBuilder.equal(root.get("status"), query.getStatus()));
                }
                if (query.getSourceScopeType() != null) {
                    predicates.add(criteriaBuilder.equal(root.get("sourceScopeType"), query.getSourceScopeType()));
                }
                if (query.getResearchProjectId() != null) {
                    predicates.add(criteriaBuilder.equal(root.get("researchProjectId"), query.getResearchProjectId()));
                }
                if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
                    String keyword = "%" + query.getKeyword().trim().toLowerCase() + "%";
                    predicates.add(criteriaBuilder.or(
                            criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), keyword),
                            criteriaBuilder.like(criteriaBuilder.lower(root.get("content")), keyword)
                    ));
                }
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
