package com.noteweave.answer.strategy;

import com.noteweave.common.BusinessException;
import java.util.Locale;

public enum AnswerMode {
    QA,
    NOTE,
    WIKI;

    public static AnswerMode parse(String value) {
        if (value == null || value.isBlank()) {
            return QA;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("ANSWER_MODE_UNSUPPORTED", "Unsupported answer mode: " + value);
        }
    }
}
