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
        return apply(prompt, verdict, bundle, "");
    }

    /** 没有单独判定充分性时附加的要求，让回答模型自己说明资料覆盖到哪里。 */
    static final String SELF_CHECK_INSTRUCTION =
            "回答前先核对资料是否覆盖了问题：资料只覆盖一部分时，只回答覆盖的部分，并明确说明缺什么；"
                    + "资料与问题无关时，直接说明现有资料不足以回答，不要编造。";

    /**
     * deferredToAnswer 为 true 表示没有单独判定充分性（verdict 为 NOT_RUN），
     * 在提示末尾加上 {@link #SELF_CHECK_INSTRUCTION}；其余情况与 {@link #apply(PromptSpec, EvidenceSufficiency, EvidenceBundle, String)} 相同。
     */
    static PromptSpec apply(PromptSpec prompt, EvidenceSufficiency verdict, EvidenceBundle bundle, String question,
                            boolean deferredToAnswer) {
        if (deferredToAnswer && prompt != null && !prompt.refusal()
                && verdict == EvidenceSufficiency.NOT_RUN) {
            return append(prompt, SELF_CHECK_INSTRUCTION);
        }
        return apply(prompt, verdict, bundle, question);
    }

    private static PromptSpec append(PromptSpec prompt, String instruction) {
        String body = prompt.userPrompt().endsWith("\n")
                ? prompt.userPrompt()
                : prompt.userPrompt() + "\n";
        return new PromptSpec(
                prompt.systemPrompt(),
                body + "\n" + instruction,
                prompt.referencedEvidenceIds(),
                prompt.promptVersion(),
                false);
    }

    static PromptSpec apply(PromptSpec prompt, EvidenceSufficiency verdict, EvidenceBundle bundle, String question) {
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
        return refusal(prompt.promptVersion(), bundle, question);
    }

    private static PromptSpec refusal(String promptVersion, EvidenceBundle bundle, String question) {
        StringBuilder user = new StringBuilder();
        if (question != null && !question.isBlank()) {
            user.append("用户问题：").append(question.strip()).append("\n\n");
        }
        user.append("现有资料不足以回答这个问题。请用两三句话告诉用户：资料里是否有与问题相关的内容（有的话简要说明涉及什么），")
                .append("还缺哪方面的信息，以及可以补充什么资料。不要编造结论，不要描述检索过程。\n");
        user.append("已选资料标题：\n");
        List<EvidenceBundle.Evidence> evidence = bundle == null ? List.of() : bundle.evidence();
        java.util.Set<String> titles = new java.util.LinkedHashSet<>();
        for (EvidenceBundle.Evidence item : evidence) {
            titles.add(item == null || item.title() == null || item.title().isBlank()
                    ? "未命名资料"
                    : item.title());
        }
        // 同一份资料的多个片段只列一次标题
        titles.forEach(title -> user.append("- ").append(title).append("\n"));
        return new PromptSpec(
                "说明现有资料不足以回答，不要编造结论。",
                user.toString(),
                List.of(),
                promptVersion,
                true);
    }
}
