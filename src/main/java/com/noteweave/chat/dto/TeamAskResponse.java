package com.noteweave.chat.dto;

import com.noteweave.citation.dto.CitationResponse;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TeamAskResponse {
    private Long userMessageId;
    private Long assistantMessageId;
    private String answer;
    private List<CitationResponse> citations;
}
