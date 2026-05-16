package com.noteweave.artifact.service;

import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactCitation;
import com.noteweave.artifact.model.ArtifactSource;
import com.noteweave.artifact.model.ArtifactSourceType;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactVersion;
import com.noteweave.artifact.model.SessionArtifact;
import com.noteweave.artifact.model.SessionArtifactRelationType;
import com.noteweave.artifact.repository.ArtifactCitationRepository;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.artifact.repository.ArtifactSourceRepository;
import com.noteweave.artifact.repository.ArtifactVersionRepository;
import com.noteweave.artifact.repository.SessionArtifactRepository;
import com.noteweave.citation.model.Citation;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ArtifactPersistenceService {

    private final ArtifactRepository artifactRepository;
    private final ArtifactVersionRepository artifactVersionRepository;
    private final ArtifactSourceRepository artifactSourceRepository;
    private final ArtifactCitationRepository artifactCitationRepository;
    private final SessionArtifactRepository sessionArtifactRepository;

    @Transactional
    public ArtifactSaveResult saveGeneratedArtifact(
            Long artifactId,
            Long taskId,
            Long userId,
            String title,
            String content,
            List<ArtifactSourceLink> sourceLinks,
            List<Citation> citations,
            Long createdFromSessionId
    ) {
        Artifact artifact = artifactRepository.findByIdForUpdate(artifactId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND));

        int nextVersionNo = artifactVersionRepository.findMaxVersionNo(artifact.getId()) + 1;
        ArtifactVersion version = new ArtifactVersion();
        version.setArtifactId(artifact.getId());
        version.setVersionNo(nextVersionNo);
        version.setTaskId(taskId);
        version.setTitle(title);
        version.setContent(content);
        version.setChangeNote(nextVersionNo == 1 ? "studio generation" : "artifact regeneration");
        version.setCreatedBy(userId);
        artifactVersionRepository.save(version);

        artifact.setTitle(version.getTitle());
        artifact.setContent(version.getContent());
        artifact.setStatus(ArtifactStatus.READY);
        artifactRepository.save(artifact);

        mergeSourceRelations(artifact.getId(), sourceLinks);
        mergeCitationRelations(artifact.getId(), citations);
        createSessionRelationIfNeeded(createdFromSessionId, artifact.getId());

        return new ArtifactSaveResult(artifact, version);
    }

    @Transactional
    public void markArtifactFailed(Long artifactId) {
        artifactRepository.findByIdForUpdate(artifactId).ifPresent(artifact -> {
            if (artifact.getDeletedAt() == null && artifact.getStatus() != ArtifactStatus.ARCHIVED) {
                artifact.setStatus(ArtifactStatus.FAILED);
                artifactRepository.save(artifact);
            }
        });
    }

    @Transactional
    public void reconcileAfterUnsuccessfulTask(Long artifactId) {
        artifactRepository.findByIdForUpdate(artifactId).ifPresent(artifact -> {
            if (artifact.getDeletedAt() != null || artifact.getStatus() == ArtifactStatus.ARCHIVED) {
                return;
            }

            artifactVersionRepository.findTopByArtifactIdOrderByVersionNoDesc(artifactId)
                    .ifPresentOrElse(version -> {
                        artifact.setTitle(version.getTitle());
                        artifact.setContent(version.getContent());
                        artifact.setStatus(ArtifactStatus.READY);
                    }, () -> artifact.setStatus(ArtifactStatus.FAILED));
            artifactRepository.save(artifact);
        });
    }

    private void mergeSourceRelations(Long artifactId, List<ArtifactSourceLink> sourceLinks) {
        for (ArtifactSourceLink link : sourceLinks) {
            artifactSourceRepository.findByArtifactIdAndSourceTypeAndSourceId(artifactId, link.sourceType(), link.sourceId())
                    .orElseGet(() -> {
                        ArtifactSource source = new ArtifactSource();
                        source.setArtifactId(artifactId);
                        source.setSourceType(link.sourceType());
                        source.setSourceId(link.sourceId());
                        return artifactSourceRepository.save(source);
                    });
        }
    }

    private void mergeCitationRelations(Long artifactId, List<Citation> citations) {
        for (Citation citation : citations) {
            artifactCitationRepository.findByArtifactIdAndCitationId(artifactId, citation.getId())
                    .orElseGet(() -> {
                        ArtifactCitation relation = new ArtifactCitation();
                        relation.setArtifactId(artifactId);
                        relation.setCitationId(citation.getId());
                        return artifactCitationRepository.save(relation);
                    });
        }
    }

    private void createSessionRelationIfNeeded(Long sessionId, Long artifactId) {
        if (sessionId == null) {
            return;
        }
        sessionArtifactRepository.findBySessionIdAndArtifactIdAndRelationType(
                        sessionId,
                        artifactId,
                        SessionArtifactRelationType.CREATED_FROM
                )
                .orElseGet(() -> {
                    SessionArtifact relation = new SessionArtifact();
                    relation.setSessionId(sessionId);
                    relation.setArtifactId(artifactId);
                    relation.setRelationType(SessionArtifactRelationType.CREATED_FROM);
                    return sessionArtifactRepository.save(relation);
                });
    }

    public record ArtifactSourceLink(ArtifactSourceType sourceType, Long sourceId) {
    }

    public record ArtifactSaveResult(Artifact artifact, ArtifactVersion version) {
    }
}
