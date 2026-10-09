package com.noteweave.chat;

import com.noteweave.answer.AnswerGenerationGateway;
import com.noteweave.answer.AnswerGenerationMaterial;
import com.noteweave.common.BusinessException;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ChatAnswerGenerationAdapter implements AnswerGenerationGateway {

    private final JdbcTemplate jdbcTemplate;
    private final ChatLlmClient chatLlmClient;
    private final boolean templateFallbackEnabled;
    private final ResearchGeneratedSourceReadGate generatedSourceGate;

    public ChatAnswerGenerationAdapter(
            JdbcTemplate jdbcTemplate,
            ChatLlmClient chatLlmClient,
            ResearchGeneratedSourceReadGate generatedSourceGate,
            @Value("${noteweave.llm.template-fallback-enabled:false}") boolean templateFallbackEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatLlmClient = chatLlmClient;
        this.generatedSourceGate = generatedSourceGate;
        this.templateFallbackEnabled = templateFallbackEnabled;
    }

    @Override
    public AnswerGenerationMaterial load(String assistantRequestId) {
        return jdbcTemplate.query("""
                select m.id, m.workspace_id, m.content,
                       coalesce(r.maximum_output_tokens, 1200) as maximum_output_tokens
                from conversation_message m
                left join answer_run r on r.assistant_request_id = m.assistant_request_id
                where m.assistant_request_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("CHAT_REQUEST_NOT_FOUND", "Chat request does not exist");
            }
            String messageId = rs.getString("id");
            String workspaceId = rs.getString("workspace_id");
            return new AnswerGenerationMaterial(
                    messageId,
                    workspaceId,
                    rs.getString("content"),
                    citationLines(workspaceId, messageId),
                    rs.getInt("maximum_output_tokens")
            );
        }, assistantRequestId);
    }

    @Override
    public String prepareDraft(String storedContent) {
        if (storedContent == null) {
            return "";
        }
        String marker = "> **[TEMPLATE PLACEHOLDER]**";
        int markerIndex = storedContent.indexOf(marker);
        if (markerIndex < 0) {
            return storedContent;
        }
        int markerEnd = storedContent.indexOf("\n\n", markerIndex);
        return markerEnd < 0 ? "" : storedContent.substring(markerEnd + 2);
    }

    @Override
    public String generate(
            String draft,
            int maximumOutputTokens,
            Consumer<String> tokenConsumer
    ) {
        if (chatLlmClient == null || !chatLlmClient.isEnabled()) {
            if (!templateFallbackEnabled) {
                throw new BusinessException(
                        "ANSWER_LLM_CONFIGURATION_REQUIRED",
                        "Answer LLM is not configured",
                        HttpStatus.SERVICE_UNAVAILABLE
                );
            }
            if (draft == null || draft.isBlank()) {
                throw new BusinessException(
                        "ANSWER_TEMPLATE_EMPTY_RESPONSE",
                        "Template fallback has no answer content",
                        HttpStatus.BAD_GATEWAY
                );
            }
            replayChunks(draft).forEach(tokenConsumer);
            return draft;
        }
        // 前端按草稿里的二级标题拆出页面关系、来源等折叠卡片，模型润色时必须保留这些标题
        String systemPrompt = """
                你是 NoteWeave 知识工作台的统一聊天助手。用户消息是系统整理的回答草稿，或者回答要求加资料片段，请据此写出自然通顺的中文回答。
                回答必须基于其中的资料证据与检索元信息，不要编造未在证据中出现的来源、链接、引用或数字。
                草稿中每个以 ## 开头的二级标题都要原样保留，名称和顺序不变，只改写标题下面的内容；不要新增草稿里没有的二级标题。
                保留引用标注（例如 [1]、[[页面名]]）和列表结构。
                不要在正文里复述系统内部字段，例如 RESOLVED、UNRESOLVED、出链数、反链数、来源计数、chunk 编号。
                最终结果直接作为聊天正文，不要加前言或解释。
                """.trim();
        String[] streamedBuffer = new String[1];
        String streamed = chatLlmClient.streamChat(
                systemPrompt,
                draft == null ? "" : draft,
                maximumOutputTokens,
                token -> {
                    if (token == null || token.isEmpty()) {
                        return;
                    }
                    streamedBuffer[0] = streamedBuffer[0] == null
                            ? token : streamedBuffer[0] + token;
                    tokenConsumer.accept(token);
                });
        String generated = streamed == null || streamed.isEmpty() ? streamedBuffer[0] : streamed;
        if (generated == null || generated.isBlank()) {
            throw new BusinessException(
                    "ANSWER_LLM_EMPTY_RESPONSE",
                    "Answer LLM returned no content",
                    HttpStatus.BAD_GATEWAY
            );
        }
        return generated;
    }

    @Override
    public List<String> replayChunks(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String[] lines = content.split("\n", -1);
        int linesPerChunk = Math.max(1, (lines.length + 5) / 6);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int lineCount = 0;
        for (int index = 0; index < lines.length; index++) {
            current.append(lines[index]);
            lineCount++;
            boolean last = index == lines.length - 1;
            if (lineCount >= linesPerChunk || last) {
                chunks.add(current.toString());
                current = new StringBuilder();
                lineCount = 0;
            }
            if (!last) {
                current.append("\n");
            }
        }
        return chunks;
    }

    @Override
    public void persistContent(String workspaceId, String messageId, String content) {
        jdbcTemplate.update("""
                update conversation_message set content = ? where workspace_id = ? and id = ?
                """, content, workspaceId, messageId);
    }

    @Override
    public String configuredModel() {
        if (chatLlmClient != null && chatLlmClient.isEnabled()) {
            return "configured-llm";
        }
        return templateFallbackEnabled ? "template-fallback" : "unconfigured";
    }

    private List<String> citationLines(String workspaceId, String messageId) {
        return jdbcTemplate.query("""
                select c.title, c.quote_text, coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id
                from message_citation mc
                join citation c on c.id = mc.citation_id
                left join source s on s.id = c.source_id
                where c.workspace_id = ? and mc.message_id = ?
                order by mc.sort_order asc
                """, (rs, rowNum) -> {
            String generatedBy = rs.getString("generated_by");
            String generatedRefId = rs.getString("generated_ref_id");
            generatedSourceGate.requireReadable(workspaceId, generatedBy, generatedRefId);
            return displayTitle(rs.getString("title"), generatedBy, generatedRefId)
                    + " | " + rs.getString("quote_text");
        }, workspaceId, messageId);
    }

    private String displayTitle(String title, String generatedBy, String generatedRefId) {
        if ("research_agent".equals(generatedBy)) {
            return title + " · Research Report(" + (
                    generatedRefId == null || generatedRefId.isBlank() ? "unknown run" : generatedRefId) + ")";
        }
        return title;
    }
}
