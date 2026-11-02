package com.noteweave.answer.strategy;

/** 未评分尾部的来源，不从模型别名或降级日志推断评分生产者。 */
public final class ScoreOrigins {
    private ScoreOrigins() { }
    public static ScoreInfo fusionFallback() { return ScoreInfo.fusion(); }
}
