package com.noteweave.knowledge;

import java.util.List;

/** Wiki 词项检索的字段加权。must 提高整词权重，title 偏标题，body 偏正文。 */
public record WikiLexicalBoost(List<String> mustTerms, List<String> titleTerms, List<String> bodyTerms) {
    public WikiLexicalBoost {
        mustTerms = mustTerms == null ? List.of() : List.copyOf(mustTerms);
        titleTerms = titleTerms == null ? List.of() : List.copyOf(titleTerms);
        bodyTerms = bodyTerms == null ? List.of() : List.copyOf(bodyTerms);
    }

    public static WikiLexicalBoost none() {
        return new WikiLexicalBoost(List.of(), List.of(), List.of());
    }

    public boolean empty() {
        return mustTerms.isEmpty() && titleTerms.isEmpty() && bodyTerms.isEmpty();
    }
}
