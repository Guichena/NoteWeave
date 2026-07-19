package com.noteweave.chat;

import com.noteweave.common.BusinessException;
import com.noteweave.source.GeneratedSourceResult;
import com.noteweave.source.GeneratedSourceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteAnswerSourceService {

    public static final String GENERATED_BY = "note_answer";
    public static final String SOURCE_TYPE = "GENERATED_NOTE_ANSWER";

    private final JdbcTemplate jdbcTemplate;
    private final GeneratedSourceService generatedSourceService;
    private final NoteNeutralMarkdownRewriter rewriter;
    private final ChatLlmClient chatLlmClient;

    public NoteAnswerSourceService(
            JdbcTemplate jdbcTemplate,
            GeneratedSourceService generatedSourceService,
            NoteNeutralMarkdownRewriter rewriter,
            ChatLlmClient chatLlmClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.generatedSourceService = generatedSourceService;
        this.rewriter = rewriter;
        this.chatLlmClient = chatLlmClient;
    }

    public NoteSourceDraftResponse buildSourceDraft(String messageId, NoteSourceDraftRequest request) {
        MessageRow message = requireNoteAssistantMessage(messageId);
        String title = request == null || request.title() == null || request.title().isBlank()
                ? defaultTitle(message.content())
                : request.title().trim();
        NoteNeutralMarkdownRewriter.RewriteResult rewritten = rewriter.polishWithLlm(
                chatLlmClient,
                title,
                message.content()
        );
        return new NoteSourceDraftResponse(
                messageId,
                rewritten.title(),
                rewritten.content(),
                rewritten.mode(),
                message.content()
        );
    }

    @Transactional
    public SaveMessageAsSourceResponse saveAsSource(String messageId, SaveMessageAsSourceRequest request) {
        MessageRow message = requireNoteAssistantMessage(messageId);
        String title = request.title() == null ? "" : request.title().trim();
        if (title.isBlank()) {
            throw new BusinessException("SOURCE_TITLE_REQUIRED", "资料标题不能为空");
        }
        String content = resolveContent(request.content(), message.content(), title);
        if (content.isBlank()) {
            throw new BusinessException("SOURCE_CONTENT_EMPTY", "入库正文为空，请先完成 Note 回答或填写正文");
        }
        GeneratedSourceResult source = generatedSourceService.saveMarkdown(
                message.workspaceId(),
                title,
                content,
                SOURCE_TYPE,
                GENERATED_BY,
                messageId
        );
        return new SaveMessageAsSourceResponse(
                source.sourceId(),
                messageId,
                title,
                source.status(),
                source.parseStatus(),
                source.indexStatus(),
                source.generatedBy(),
                source.generatedRefId()
        );
    }

    private String resolveContent(String requestContent, String messageContent, String title) {
        if (requestContent != null && !requestContent.isBlank()) {
            return requestContent.trim();
        }
        return rewriter.rewrite(title, messageContent).content();
    }

    private String defaultTitle(String content) {
        if (content == null || content.isBlank()) {
            return "工作台整理笔记";
        }
        String firstLine = content.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("工作台整理笔记");
        if (firstLine.startsWith("#")) {
            firstLine = firstLine.replaceFirst("^#+\\s*", "").trim();
        }
        if (firstLine.length() > 80) {
            return firstLine.substring(0, 80);
        }
        return firstLine.isBlank() ? "工作台整理笔记" : firstLine;
    }

    private MessageRow requireNoteAssistantMessage(String messageId) {
        MessageRow message = loadAssistantMessage(messageId);
        if (!"NOTE".equals(message.answerMode())) {
            throw new BusinessException(
                    "MESSAGE_NOT_NOTE_ANSWER",
                    "只有 Note 模式的助手回答可以整理或入库为资料"
            );
        }
        return message;
    }

    private MessageRow loadAssistantMessage(String messageId) {
        return jdbcTemplate.query("""
                select id, workspace_id, role, coalesce(answer_mode, '') as answer_mode, content
                from conversation_message
                where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("MESSAGE_NOT_FOUND", "消息不存在");
            }
            if (!"ASSISTANT".equals(rs.getString("role"))) {
                throw new BusinessException("MESSAGE_NOT_ASSISTANT", "只能把助手回答入库为资料");
            }
            return new MessageRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("answer_mode").trim().toUpperCase(),
                    rs.getString("content")
            );
        }, messageId);
    }

    private record MessageRow(
            String messageId,
            String workspaceId,
            String answerMode,
            String content
    ) {
    }
}
