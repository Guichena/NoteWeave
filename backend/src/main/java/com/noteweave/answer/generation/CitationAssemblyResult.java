package com.noteweave.answer.generation;

import java.util.List;

/**
 * 引用落库的结果：这次回答实际生成了哪些引用行，以及编号到证据身份的绑定。
 *
 * <p>绑定顺序与提示里 {@code [n]} 的编号一致，流式规范化、落库正文和前端角标都按同一份绑定解析。</p>
 */
public record CitationAssemblyResult(
        List<CitationBinding> bindings,
        int persistedCount
) {
    public CitationAssemblyResult {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    public static CitationAssemblyResult empty() {
        return new CitationAssemblyResult(List.of(), 0);
    }

    /** 该证据对应的引用行编号；知识页可能绑定多个来源引用。 */
    public List<String> citationIdsOf(String evidenceId) {
        return bindings.stream()
                .filter(binding -> binding.evidenceId().equals(evidenceId))
                .flatMap(binding -> binding.citationIds().stream())
                .toList();
    }
}
