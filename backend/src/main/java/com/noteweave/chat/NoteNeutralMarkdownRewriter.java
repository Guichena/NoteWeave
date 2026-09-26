package com.noteweave.chat;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 将 Note 模式回答整理为可入库的中性 Markdown。
 * <p>
 * 默认走确定性模板：抽出「直接回答」主结论，去掉检索过程卡片与聊天腔。
 * LLM 可用时由 {@link NoteAnswerSourceService} 再尝试润色，失败则回退本模板结果。
 */
@Component
public class NoteNeutralMarkdownRewriter {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final List<String> PROCESS_HEADINGS = List.of(
            "资料定位",
            "深读窗口",
            "摘录证据",
            "来源引用",
            "继续追问",
            "journal 信号",
            "验证摘要",
            "定位说明"
    );

    private final boolean templateFallbackEnabled;
    private final MeterRegistry meterRegistry;

    public NoteNeutralMarkdownRewriter(
            @Value("${noteweave.llm.template-fallback-enabled:false}") boolean templateFallbackEnabled,
            MeterRegistry meterRegistry
    ) {
        this.templateFallbackEnabled = templateFallbackEnabled;
        this.meterRegistry = meterRegistry;
    }

    public RewriteResult rewrite(String title, String answerContent) {
        String safeTitle = normalizeTitle(title);
        String source = answerContent == null ? "" : answerContent.replace("\r\n", "\n").trim();
        if (source.isBlank()) {
            return new RewriteResult(safeTitle, "", "template", "");
        }
        String directAnswer = extractDirectAnswer(source);
        String cleaned = stripProcessNoise(directAnswer);
        cleaned = stripChatVoice(cleaned);
        cleaned = collapseBlankLines(cleaned).trim();
        if (cleaned.isBlank()) {
            cleaned = stripProcessNoise(source);
            cleaned = stripChatVoice(cleaned);
            cleaned = collapseBlankLines(cleaned).trim();
        }
        String body = cleaned.isBlank() ? source : cleaned;
        String markdown = "# " + safeTitle + "\n\n" + body;
        return new RewriteResult(safeTitle, markdown.trim(), "template", "");
    }

    public RewriteResult polishWithLlm(ChatLlmClient llmClient, String title, String answerContent) {
        RewriteResult template = rewrite(title, answerContent);
        if (template.content().isBlank()) {
            return template;
        }
        if (llmClient == null || !llmClient.isEnabled()) {
            return fallbackOrThrow(template, "NOTE_POLISH_LLM_UNAVAILABLE");
        }
        try {
            String systemPrompt = """
                    你是研究工作台的知识整理助手。
                    任务：把 Note 模式回答改写成可进入资料池的中性 Markdown 知识点。
                    要求：
                    1. 只保留稳定结论与可复用知识点，去掉聊天口吻、第一人称建议、检索过程、Journal/资料定位/深读窗口/摘录证据等过程卡片。
                    2. 使用 Markdown：以一级标题开头，随后用简洁段落或列表组织。
                    3. 不编造资料中没有的事实；不确定处用中性表述保留。
                    4. 只输出 Markdown 正文，不要解释你的改写过程。
                    """;
            String userPrompt = """
                    建议标题：%s

                    原始 Note 回答：
                    ---
                    %s
                    ---
                    """.formatted(template.title(), answerContent == null ? "" : answerContent.trim());
            String polished = llmClient.streamChat(systemPrompt, userPrompt, 1800, token -> {
            });
            String normalized = normalizeLlmMarkdown(template.title(), polished);
            if (normalized.isBlank() || normalized.length() < 20) {
                return fallbackOrThrow(template, "NOTE_POLISH_LLM_INVALID_RESPONSE");
            }
            return new RewriteResult(template.title(), normalized, "llm", "");
        } catch (RuntimeException ex) {
            return fallbackOrThrow(template, "NOTE_POLISH_LLM_FAILED");
        }
    }

    private RewriteResult fallbackOrThrow(RewriteResult template, String reason) {
        if (!templateFallbackEnabled) {
            throw new BusinessException(
                    "NOTE_POLISH_LLM_FAILED",
                    "Note polish LLM is unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        meterRegistry.counter("noteweave.note.polish.fallback", "reason", reason).increment();
        return new RewriteResult(template.title(), template.content(), "template", reason);
    }

    private String extractDirectAnswer(String source) {
        String[] lines = source.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String heading = headingText(lines[i]);
            if (heading != null && (heading.contains("直接回答") || heading.equalsIgnoreCase("answer"))) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return source;
        }
        List<String> body = new ArrayList<>();
        for (int i = start; i < lines.length; i++) {
            String heading = headingText(lines[i]);
            if (heading != null && isProcessHeading(heading)) {
                break;
            }
            body.add(lines[i]);
        }
        return String.join("\n", body).trim();
    }

    private String stripProcessNoise(String content) {
        String[] lines = content.split("\n", -1);
        List<String> kept = new ArrayList<>();
        boolean skipping = false;
        for (String line : lines) {
            String heading = headingText(line);
            if (heading != null) {
                skipping = isProcessHeading(heading);
                if (skipping) {
                    continue;
                }
            }
            if (skipping) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("【") && (
                    trimmed.contains("Journal")
                            || trimmed.contains("候选资料")
                            || trimmed.contains("关系扩展")
                            || trimmed.contains("验证摘要")
                            || trimmed.contains("定位说明")
                            || trimmed.contains("原文窗口")
            )) {
                skipping = true;
                continue;
            }
            if (trimmed.startsWith("- 呈现策略")
                    || trimmed.startsWith("- 当前问题")
                    || trimmed.startsWith("- 会话上下文")
                    || trimmed.startsWith("- candidate_")
                    || trimmed.startsWith("- trace:")
                    || trimmed.startsWith("- verify")
                    || trimmed.contains("selection_reason=")
                    || trimmed.contains("verify_admission_reason=")
                    || trimmed.contains("query_coverage=")
                    || trimmed.contains("matched_fields=")) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    private String stripChatVoice(String content) {
        String[] lines = content.split("\n", -1);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                kept.add(line);
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("当然")
                    || lower.startsWith("好的")
                    || lower.startsWith("没问题")
                    || lower.startsWith("根据你的问题")
                    || lower.startsWith("让我来")
                    || lower.startsWith("我认为你可以")
                    || lower.startsWith("你可以这样")
                    || lower.startsWith("希望这能帮助")
                    || lower.startsWith("如需继续")
                    || lower.startsWith("如果还需要")) {
                continue;
            }
            kept.add(line
                    .replace("我认为", "")
                    .replace("我觉得", "")
                    .replace("你可以考虑", "")
                    .replace("建议你", ""));
        }
        return String.join("\n", kept);
    }

    private String normalizeLlmMarkdown(String title, String polished) {
        if (polished == null) {
            return "";
        }
        String text = polished.replace("\r\n", "\n").trim();
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        if (!text.startsWith("#")) {
            text = "# " + normalizeTitle(title) + "\n\n" + text;
        }
        return collapseBlankLines(text).trim();
    }

    private String normalizeTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isBlank()) {
            return "工作台整理笔记";
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private String headingText(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = HEADING.matcher(line.trim());
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1).trim();
    }

    private boolean isProcessHeading(String heading) {
        String lower = heading.toLowerCase(Locale.ROOT);
        for (String process : PROCESS_HEADINGS) {
            if (lower.contains(process.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String collapseBlankLines(String content) {
        return content.replaceAll("\n{3,}", "\n\n");
    }

    public record RewriteResult(String title, String content, String mode, String fallbackReason) {
    }
}
