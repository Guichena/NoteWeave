package com.noteweave.answer.generation;

import java.util.List;

/**
 * 一条编号到证据身份的绑定。
 *
 * <p>模型在正文里写 {@code [n]}，{@code n} 通过这份绑定解析成具体证据。编号只在本次回答内有效，
 * 不让模型写证据 ID，避免模型编造或混用跨回答的证据键。</p>
 */
public record CitationBinding(
        int number,
        String evidenceId,
        List<String> citationIds
) {
    public CitationBinding {
        citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
    }
}
