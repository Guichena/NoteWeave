package com.noteweave.personal.project.service;

import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.model.ResearchProjectCompileStatus;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import com.noteweave.personal.project.repository.ResearchProjectRepository;
import com.noteweave.personal.source.model.Source;
import com.noteweave.personal.source.model.SourceCompileStatus;
import com.noteweave.personal.source.model.SourceImportStatus;
import com.noteweave.personal.source.repository.SourceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResearchProjectCompileStatusService {

    private final ResearchProjectRepository researchProjectRepository;
    private final SourceRepository sourceRepository;

    @Transactional
    public void refresh(Long projectId) {
        ResearchProject project = researchProjectRepository.findByIdForUpdate(projectId).orElse(null);
        if (project == null || project.getDeletedAt() != null || project.getStatus() != ResearchProjectStatus.ACTIVE) {
            return;
        }

        ResearchProjectCompileStatus nextStatus = calculate(
                sourceRepository.findByResearchProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(projectId)
        );
        if (project.getCompileStatus() != nextStatus) {
            project.setCompileStatus(nextStatus);
            researchProjectRepository.save(project);
        }
    }

    ResearchProjectCompileStatus calculate(List<Source> sources) {
        if (sources.isEmpty()) {
            return ResearchProjectCompileStatus.PENDING;
        }
        if (sources.stream().anyMatch(source -> source.getCompileStatus() == SourceCompileStatus.COMPILING)) {
            return ResearchProjectCompileStatus.COMPILING;
        }
        if (sources.stream().anyMatch(source -> source.getImportStatus() != SourceImportStatus.READY)) {
            return ResearchProjectCompileStatus.PENDING;
        }
        if (sources.stream().anyMatch(source -> source.getCompileStatus() == SourceCompileStatus.FAILED)) {
            return ResearchProjectCompileStatus.FAILED;
        }
        if (sources.stream().allMatch(source -> source.getCompileStatus() == SourceCompileStatus.READY)) {
            return ResearchProjectCompileStatus.READY;
        }
        return ResearchProjectCompileStatus.PENDING;
    }
}
