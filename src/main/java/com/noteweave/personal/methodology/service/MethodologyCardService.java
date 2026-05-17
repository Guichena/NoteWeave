package com.noteweave.personal.methodology.service;

import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.methodology.MethodologyCardJsonMapper;
import com.noteweave.personal.methodology.dto.MethodologyCardResponse;
import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.methodology.model.MethodologyCardSource;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import com.noteweave.personal.methodology.repository.MethodologyCardRepository;
import com.noteweave.personal.project.service.ResearchProjectService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MethodologyCardService {

    private final MethodologyCardRepository methodologyCardRepository;
    private final ResearchProjectService researchProjectService;
    private final PersonalSpaceService personalSpaceService;
    private final MethodologyCardJsonMapper jsonMapper;

    @Transactional(readOnly = true)
    public List<MethodologyCardResponse> list(Long userId, Long researchProjectId) {
        researchProjectService.getRequiredActiveProject(userId, researchProjectId);
        Long personalSpaceId = personalSpaceService.getRequiredPersonalSpace(userId).getId();

        List<MethodologyCard> cards = new ArrayList<>();
        cards.addAll(methodologyCardRepository.findByResearchProjectIdAndStatusOrderByUpdatedAtDesc(
                researchProjectId,
                MethodologyCardStatus.ACTIVE
        ));
        cards.addAll(methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndStatusOrderByUpdatedAtDesc(
                personalSpaceId,
                MethodologyCardStatus.ACTIVE
        ));
        cards.addAll(methodologyCardRepository.findByCardSourceAndStatusOrderByUpdatedAtDesc(
                MethodologyCardSource.PRESET,
                MethodologyCardStatus.ACTIVE
        ));
        return cards.stream().map(this::toResponse).toList();
    }

    private MethodologyCardResponse toResponse(MethodologyCard card) {
        return MethodologyCardResponse.builder()
                .id(card.getId())
                .spaceId(card.getSpaceId())
                .researchProjectId(card.getResearchProjectId())
                .name(card.getName())
                .scene(card.getScene())
                .problemType(card.getProblemType())
                .workflow(jsonMapper.readList(card.getWorkflowJson()))
                .outputStructure(jsonMapper.readList(card.getOutputStructureJson()))
                .qualityChecklist(jsonMapper.readList(card.getQualityChecklistJson()))
                .cardSource(card.getCardSource())
                .status(card.getStatus())
                .version(card.getVersion())
                .createdAt(card.getCreatedAt())
                .updatedAt(card.getUpdatedAt())
                .build();
    }
}
