package com.noteweave.chat;

import com.noteweave.answer.EvidenceSufficiency;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import java.util.List;

/** 按充分性调整生成提示。SUFFICIENT 与 NOT_RUN 保持策略原文。 */
final class EvidenceSufficiencyPrompts {
    static final String PARTIAL_INSTRUCTION =
            "资料只覆盖了问题的一部分。请只回答资料已经覆盖的部分，没有覆盖的部分要明确说明缺什么。";

    private EvidenceSufficiencyPrompts() {
    }

    static PromptSpec apply(PromptSpec prompt, EvidenceSufficiency verdict, EvidenceBundle bundle) {
        if (prompt == null || verdict == null
                || verdict == EvidenceSufficiency.SUFFICIENT
                || verdict == EvidenceSufficiency.NOT_RUN
                || prompt.refusal()) {
            return prompt;
        }
        if (verdict == EvidenceSufficiency.PARTIAL) {
            String body = prompt.userPrompt().endsWith("\n")
                    ? prompt.userPrompt()
                    : prompt.userPrompt() + "\n";
            return new PromptSpec(
                    prompt.systemPrompt(),
                    body + "\n" + PARTIAL_INSTRUCTION,
                    prompt.referencedEvidenceIds(),
                    prompt.promptVersion(),
                    false);
        }
        return refusal(prompt.promptVersion(), bundle);
    }

    private static PromptSpec refusal(String promptVersion, EvidenceBundle bundle) {
        StringBuilder user = new StringBuilder();
        user.append("现有资料不足以回答这个问题。请说明资料不足，不要编造结论。\n");
        user.append("已选资料标题：\n");
        List<EvidenceBundle.Evidence> evidence = bundle == null ? List.of() : bundle.evidence();
        for (EvidenceBundle.Evidence item : evidence) {
            String title = item == null || item.title() == null || item.title().isBlank()
                    ? "未命名资料"
                    : item.title();
            user.append("- ").append(title).append("\n");
        }
        return new PromptSpec(
                "说明现有资料不足以回答，不要编造结论。",
                user.toString(),
                List.of(),
                promptVersion,
                true);
    }
}
