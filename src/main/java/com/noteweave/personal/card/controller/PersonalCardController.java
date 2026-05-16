package com.noteweave.personal.card.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.personal.card.dto.ArticleCardResponse;
import com.noteweave.personal.card.dto.ConceptCardResponse;
import com.noteweave.personal.card.dto.MergeConceptCardsRequest;
import com.noteweave.personal.card.dto.UpdateConceptCardRequest;
import com.noteweave.personal.card.service.ArticleCardService;
import com.noteweave.personal.card.service.ConceptCardService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/personal")
@RequiredArgsConstructor
public class PersonalCardController {

    private final ArticleCardService articleCardService;
    private final ConceptCardService conceptCardService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/research-projects/{projectId}/article-cards")
    public ApiResponse<List<ArticleCardResponse>> listArticleCards(
            @PathVariable Long projectId,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return ApiResponse.success(articleCardService.list(currentUserProvider.getCurrentUserId(), projectId, keyword));
    }

    @GetMapping("/article-cards/{cardId}")
    public ApiResponse<ArticleCardResponse> getArticleCard(@PathVariable Long cardId) {
        return ApiResponse.success(articleCardService.get(currentUserProvider.getCurrentUserId(), cardId));
    }

    @GetMapping("/research-projects/{projectId}/concept-cards")
    public ApiResponse<List<ConceptCardResponse>> listConceptCards(
            @PathVariable Long projectId,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return ApiResponse.success(conceptCardService.list(currentUserProvider.getCurrentUserId(), projectId, keyword));
    }

    @GetMapping("/concept-cards/{cardId}")
    public ApiResponse<ConceptCardResponse> getConceptCard(@PathVariable Long cardId) {
        return ApiResponse.success(conceptCardService.get(currentUserProvider.getCurrentUserId(), cardId));
    }

    @PutMapping("/concept-cards/{cardId}")
    public ApiResponse<ConceptCardResponse> updateConceptCard(
            @PathVariable Long cardId,
            @Valid @RequestBody UpdateConceptCardRequest request
    ) {
        return ApiResponse.success(conceptCardService.update(currentUserProvider.getCurrentUserId(), cardId, request));
    }

    @PostMapping("/concept-cards/merge")
    public ApiResponse<ConceptCardResponse> mergeConceptCards(@Valid @RequestBody MergeConceptCardsRequest request) {
        return ApiResponse.success(conceptCardService.merge(currentUserProvider.getCurrentUserId(), request));
    }
}
