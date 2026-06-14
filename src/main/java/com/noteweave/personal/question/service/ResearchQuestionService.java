package com.noteweave.personal.question.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.personal.question.dto.CreateResearchQuestionRequest;
import com.noteweave.personal.question.dto.ResearchQuestionResponse;
import com.noteweave.personal.question.dto.UpdateResearchQuestionRequest;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.model.ResearchQuestionStatus;
import com.noteweave.personal.question.repository.ResearchQuestionRepository;
import com.noteweave.space.model.Space;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResearchQuestionService {

    private final ResearchQuestionRepository researchQuestionRepository;
    private final ResearchProjectService researchProjectService;
    private final PersonalSpaceService personalSpaceService;

    @Transactional
    public ResearchQuestionResponse create(Long userId, CreateResearchQuestionRequest request) {
        ResearchProject project = researchProjectService.getRequiredActiveProject(userId, request.getResearchProjectId());
        ResearchQuestion question = new ResearchQuestion();
        question.setSpaceId(project.getSpaceId());
        question.setUserId(userId);
        question.setResearchProjectId(project.getId());
        question.setTitle(normalizeRequired(request.getTitle()));
        question.setQuestionType(normalizeOptional(request.getQuestionType()));
        question.setCurrentHypothesis(normalizeOptional(request.getCurrentHypothesis()));
        question.setNextStep(normalizeOptional(request.getNextStep()));
        question.setScopeNote(normalizeOptional(request.getScopeNote()));
        question.setStatus(ResearchQuestionStatus.OPEN);
        return toResponse(researchQuestionRepository.save(question));
    }

    @Transactional(readOnly = true)
    public List<ResearchQuestionResponse> listByProject(Long userId, Long researchProjectId) {
        // Validate ownership of the parent project before exposing its questions.
        researchProjectService.getRequiredActiveProject(userId, researchProjectId);
        return researchQuestionRepository.findByResearchProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(researchProjectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ResearchQuestionResponse get(Long userId, Long questionId) {
        return toResponse(getRequiredQuestion(userId, questionId));
    }

    @Transactional
    public ResearchQuestionResponse update(Long userId, Long questionId, UpdateResearchQuestionRequest request) {
        ResearchQuestion question = getRequiredQuestionForWrite(userId, questionId);
        question.setTitle(normalizeRequired(request.getTitle()));
        question.setQuestionType(normalizeOptional(request.getQuestionType()));
        question.setStatus(request.getStatus());
        question.setCurrentHypothesis(normalizeOptional(request.getCurrentHypothesis()));
        question.setCurrentAnswer(normalizeOptional(request.getCurrentAnswer()));
        question.setNextStep(normalizeOptional(request.getNextStep()));
        question.setScopeNote(normalizeOptional(request.getScopeNote()));
        return toResponse(researchQuestionRepository.save(question));
    }

    @Transactional
    public void archive(Long userId, Long questionId) {
        ResearchQuestion question = getRequiredQuestionForWrite(userId, questionId);
        question.setStatus(ResearchQuestionStatus.ARCHIVED);
        question.setDeletedAt(LocalDateTime.now());
        question.setDeletedBy(userId);
        researchQuestionRepository.save(question);
    }

    @Transactional(readOnly = true)
    public ResearchQuestion getRequiredQuestion(Long userId, Long questionId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        return researchQuestionRepository.findByIdAndSpaceIdAndDeletedAtIsNull(questionId, personalSpace.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESEARCH_QUESTION_NOT_FOUND));
    }

    @Transactional
    public ResearchQuestion getRequiredQuestionForWrite(Long userId, Long questionId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        ResearchQuestion question = researchQuestionRepository.findByIdForUpdate(questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESEARCH_QUESTION_NOT_FOUND));
        if (question.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.RESEARCH_QUESTION_NOT_FOUND);
        }
        if (!question.getSpaceId().equals(personalSpace.getId())) {
            throw new BusinessException(ErrorCode.RESEARCH_QUESTION_ACCESS_DENIED, "No permission to access this research question");
        }
        return question;
    }

    private ResearchQuestionResponse toResponse(ResearchQuestion question) {
        return ResearchQuestionResponse.builder()
                .id(question.getId())
                .spaceId(question.getSpaceId())
                .userId(question.getUserId())
                .researchProjectId(question.getResearchProjectId())
                .title(question.getTitle())
                .questionType(question.getQuestionType())
                .status(question.getStatus())
                .currentHypothesis(question.getCurrentHypothesis())
                .currentAnswer(question.getCurrentAnswer())
                .nextStep(question.getNextStep())
                .scopeNote(question.getScopeNote())
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private String normalizeRequired(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
