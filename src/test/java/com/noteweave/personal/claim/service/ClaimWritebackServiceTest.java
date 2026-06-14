package com.noteweave.personal.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import com.noteweave.citation.dto.CitationResponse;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.ObservedLlmGateway;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.repository.ClaimCitationRepository;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.service.ResearchQuestionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClaimWritebackServiceTest {

    @Mock
    private ResearchQuestionService researchQuestionService;

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private ClaimConceptRelationRepository claimConceptRelationRepository;

    @Mock
    private ClaimCitationRepository claimCitationRepository;

    @Mock
    private com.noteweave.personal.card.repository.ConceptCardRepository conceptCardRepository;

    @Mock
    private ObservedLlmGateway observedLlmGateway;

    @Mock
    private ClaimIndexService claimIndexService;

    private ClaimWritebackService service;

    @BeforeEach
    void setUp() {
        service = new ClaimWritebackService(
                new ClaimWritebackStrategy(),
                researchQuestionService,
                claimRepository,
                claimConceptRelationRepository,
                claimCitationRepository,
                conceptCardRepository,
                observedLlmGateway,
                new ObjectMapper(),
                claimIndexService
        );
        lenient().when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> {
            Claim claim = invocation.getArgument(0);
            if (claim.getId() == null) {
                claim.setId(123L);
            }
            return claim;
        });
    }

    @Test
    void shouldGeneratePendingReviewClaimsForBoundQuestion() {
        ChatSession session = session(9L, 88L);
        ChatMessage userMessage = message(1000L, session.getId(), ChatMessageRole.USER, "Should we use GraphRAG for the personal wiki MVP?");
        ChatMessage assistantMessage = message(1001L, session.getId(), ChatMessageRole.ASSISTANT, "Conclusion: GraphRAG is useful for multi-hop retrieval, but it is too heavy for the MVP right now.");
        ResearchQuestion question = question(88L, "Is GraphRAG worth it?");
        CitationResponse citation = CitationResponse.builder().id(77L).title("Retriever note").quoteText("GraphRAG improves multi-hop retrieval.").build();

        given(researchQuestionService.getRequiredQuestion(7L, 88L)).willReturn(question);
        given(observedLlmGateway.chat(any(), any())).willReturn(new ObservedLlmGateway.ObservedLlmResult(
                1L,
                LlmResponse.builder()
                        .content("""
                                {"claims":[{"statement":"GraphRAG is valuable for multi-hop retrieval but too heavy for the MVP.","claimType":"CONCLUSION","stance":"SUPPORTED","confidence":0.78,"rationale":"The answer explicitly balances value and cost.","conceptNames":["GraphRAG"],"citationIds":[77]}]}
                                """)
                        .build()
        ));
        given(conceptCardRepository.findByResearchProjectIdAndNormalizedNameIn(any(), anyCollection()))
                .willReturn(List.of(concept(5L, "GraphRAG", "graphrag")));
        given(claimRepository.existsByWritebackKeyAndDeletedAtIsNull(any())).willReturn(false);

        service.writeAfterRound(session, userMessage, assistantMessage, List.of(citation));

        verify(claimRepository).save(any(Claim.class));
        verify(claimConceptRelationRepository).save(any());
        verify(claimCitationRepository).save(any());
        verify(claimIndexService).syncClaim(any(Claim.class));
    }

    @Test
    void shouldSkipWhenSessionIsNotBoundToQuestion() {
        ChatSession session = session(9L, null);
        ChatMessage userMessage = message(1000L, session.getId(), ChatMessageRole.USER, "Question");
        ChatMessage assistantMessage = message(1001L, session.getId(), ChatMessageRole.ASSISTANT, "A sufficiently detailed answer that should still be skipped.");

        service.writeAfterRound(session, userMessage, assistantMessage, List.of());

        verify(observedLlmGateway, never()).chat(any(), any());
        verify(claimRepository, never()).save(any());
    }

    @Test
    void shouldAvoidDuplicateInsertionWhenSameRoundRunsTwice() {
        ChatSession session = session(9L, 88L);
        ChatMessage userMessage = message(1000L, session.getId(), ChatMessageRole.USER, "Should we use GraphRAG?");
        ChatMessage assistantMessage = message(1001L, session.getId(), ChatMessageRole.ASSISTANT, "GraphRAG is useful, but maybe too heavy for MVP.");
        ResearchQuestion question = question(88L, "Is GraphRAG worth it?");

        given(researchQuestionService.getRequiredQuestion(7L, 88L)).willReturn(question);
        given(observedLlmGateway.chat(any(), any())).willReturn(new ObservedLlmGateway.ObservedLlmResult(
                1L,
                LlmResponse.builder()
                        .content("""
                                {"claims":[{"statement":"GraphRAG may be too heavy for the MVP.","claimType":"HYPOTHESIS","stance":"UNCERTAIN","confidence":0.61,"rationale":"The answer is cautious.","conceptNames":[],"citationIds":[]}]}
                                """)
                        .build()
        ));
        given(claimRepository.existsByWritebackKeyAndDeletedAtIsNull(any()))
                .willReturn(false)
                .willReturn(true);
        service.writeAfterRound(session, userMessage, assistantMessage, List.of());
        service.writeAfterRound(session, userMessage, assistantMessage, List.of());

        verify(claimRepository).save(any(Claim.class));
    }

    private ChatSession session(Long id, Long questionId) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId(7L);
        session.setSpaceId(1L);
        session.setResearchProjectId(9L);
        session.setResearchQuestionId(questionId);
        session.setSessionKind(ChatSessionKind.FORMAL);
        return session;
    }

    private ChatMessage message(Long id, Long sessionId, ChatMessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private ResearchQuestion question(Long id, String title) {
        ResearchQuestion question = new ResearchQuestion();
        question.setId(id);
        question.setSpaceId(1L);
        question.setUserId(7L);
        question.setResearchProjectId(9L);
        question.setTitle(title);
        return question;
    }

    private ConceptCard concept(Long id, String name, String normalizedName) {
        ConceptCard card = new ConceptCard();
        card.setId(id);
        card.setName(name);
        card.setNormalizedName(normalizedName);
        return card;
    }
}
