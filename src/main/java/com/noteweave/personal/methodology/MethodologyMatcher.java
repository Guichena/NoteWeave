package com.noteweave.personal.methodology;

import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.methodology.model.MethodologyCardSource;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import com.noteweave.personal.methodology.repository.MethodologyCardRepository;
import com.noteweave.personal.project.service.ResearchProjectService;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MethodologyMatcher {

    private final MethodologyCardRepository methodologyCardRepository;
    private final ResearchProjectService researchProjectService;
    private final PersonalSpaceService personalSpaceService;

    public Optional<MethodologyCard> match(Long userId, Long researchProjectId, ArtifactType artifactType, Map<String, Object> params) {
        researchProjectService.getRequiredActiveProject(userId, researchProjectId);
        Long personalSpaceId = personalSpaceService.getRequiredPersonalSpace(userId).getId();

        Optional<MethodologyCard> projectMatch = bestMatch(
                methodologyCardRepository.findByResearchProjectIdAndStatusOrderByUpdatedAtDesc(
                        researchProjectId,
                        MethodologyCardStatus.ACTIVE
                ),
                artifactType,
                params
        );
        if (projectMatch.isPresent()) {
            return projectMatch;
        }

        Optional<MethodologyCard> spaceMatch = bestMatch(
                methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndStatusOrderByUpdatedAtDesc(
                        personalSpaceId,
                        MethodologyCardStatus.ACTIVE
                ),
                artifactType,
                params
        );
        if (spaceMatch.isPresent()) {
            return spaceMatch;
        }

        return bestMatch(
                methodologyCardRepository.findByCardSourceAndStatusOrderByUpdatedAtDesc(
                        MethodologyCardSource.PRESET,
                        MethodologyCardStatus.ACTIVE
                ),
                artifactType,
                params
        );
    }

    private Optional<MethodologyCard> bestMatch(List<MethodologyCard> candidates, ArtifactType artifactType, Map<String, Object> params) {
        return candidates.stream()
                .map(card -> new ScoredCard(card, score(card, artifactType, params)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator
                        .comparingInt(ScoredCard::score).reversed()
                        .thenComparing(scored -> scored.card().getName(), Comparator.nullsLast(String::compareTo))
                        .thenComparing(scored -> scored.card().getId(), Comparator.nullsLast(Long::compareTo)))
                .map(ScoredCard::card)
                .findFirst();
    }

    private int score(MethodologyCard card, ArtifactType artifactType, Map<String, Object> params) {
        int score = 0;
        String normalizedProblemType = normalize(card.getProblemType());
        String artifactKey = artifactType == null ? "" : artifactType.name();
        if (artifactKey.equals(normalizedProblemType)) {
            score += 100;
        } else if (isGeneral(normalizedProblemType)) {
            score += 10;
        }

        String requestScene = normalize(
                firstNonBlank(
                        params == null ? null : params.get("scenario"),
                        params == null ? null : params.get("scene"),
                        params == null ? null : params.get("topic")
                )
        );
        String cardScene = normalize(card.getScene());
        if (!requestScene.isBlank() && !cardScene.isBlank()) {
            if (requestScene.contains(cardScene) || cardScene.contains(requestScene)) {
                score += 25;
            } else if (overlap(requestScene, cardScene)) {
                score += 15;
            }
        }
        return score;
    }

    private boolean isGeneral(String value) {
        return "GENERAL".equals(value) || "GENERIC".equals(value) || "DEFAULT".equals(value);
    }

    private boolean overlap(String left, String right) {
        for (String token : left.split("\\s+")) {
            if (token.length() >= 3 && right.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "";
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private record ScoredCard(MethodologyCard card, int score) {
    }
}
