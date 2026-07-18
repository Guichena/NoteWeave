package com.noteweave.answer.strategy;

import com.noteweave.common.BusinessException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AnswerModeStrategyRegistry {

    private final Map<AnswerMode, AnswerModeStrategy> strategies;

    public AnswerModeStrategyRegistry(List<AnswerModeStrategy> candidates) {
        EnumMap<AnswerMode, AnswerModeStrategy> indexed = new EnumMap<>(AnswerMode.class);
        for (AnswerModeStrategy candidate : candidates) {
            AnswerModeStrategy previous = indexed.putIfAbsent(candidate.supports(), candidate);
            if (previous != null) {
                throw new IllegalStateException(
                        "Multiple AnswerModeStrategy implementations for " + candidate.supports());
            }
        }
        this.strategies = Map.copyOf(indexed);
    }

    public AnswerModeStrategy require(String mode) {
        return require(AnswerMode.parse(mode));
    }

    public AnswerModeStrategy require(AnswerMode mode) {
        AnswerModeStrategy strategy = strategies.get(mode);
        if (strategy == null) {
            throw new BusinessException(
                    "ANSWER_MODE_STRATEGY_MISSING",
                    "No AnswerModeStrategy is registered for " + mode
            );
        }
        return strategy;
    }

    public Map<AnswerMode, AnswerModeStrategy> registeredStrategies() {
        return strategies;
    }
}
