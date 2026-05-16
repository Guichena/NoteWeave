package com.noteweave.personal.compiler.prompt;

import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.source.model.Source;
import org.springframework.stereotype.Component;

@Component
public class WikiCompilerPromptBuilder {

    public String buildArticlePrompt(Source source, String text) {
        return """
                你是 NoteWeave 的个人 Wiki 编译器。
                请严格输出 JSON，不要输出 Markdown，不要输出解释文字。

                目标 Source:
                sourceId=%d
                title=%s

                请输出字段:
                title, summary, keyPoints, tags, evidenceQuotes

                evidenceQuotes 每项字段:
                quote, sourceId, reason

                原文:
                %s
                """.formatted(source.getId(), safe(source.getTitle()), text);
    }

    public String buildConceptPrompt(Source source, ArticleCard articleCard, String text) {
        return """
                你是 NoteWeave 的概念抽取器。
                请严格输出 JSON，不要输出 Markdown，不要输出解释文字。

                目标 Source:
                sourceId=%d
                sourceTitle=%s
                articleCardId=%d
                articleTitle=%s

                顶层字段:
                concepts, relations

                concepts 每项字段:
                name, aliases, definition, explanation, useCases, commonMisunderstandings, evidence, confidence

                evidence 字段:
                sourceId, quote

                relations 每项字段:
                sourceName, targetName, relationType, description

                原文:
                %s
                """.formatted(
                source.getId(),
                safe(source.getTitle()),
                articleCard.getId(),
                safe(articleCard.getTitle()),
                text
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
