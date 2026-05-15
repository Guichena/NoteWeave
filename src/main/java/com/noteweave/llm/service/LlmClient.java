package com.noteweave.llm.service;

import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import java.util.List;

public interface LlmClient {

    LlmResponse chat(List<LlmMessage> messages, LlmOptions options);
}
