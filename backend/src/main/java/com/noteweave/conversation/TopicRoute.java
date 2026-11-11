package com.noteweave.conversation;

/**
 * 查询改写模型对本轮用户消息的话题判断：接着当前话题、回到某个较早的话题，或开启新话题。
 * <p>
 * 冻结上下文时交给话题切分器，替代规则对这一条消息的判断；之前已落库的划分不受影响。
 * 模型没有给出判断、给出的话题不存在时，切分器照常按规则与向量判断。
 */
public record TopicRoute(Kind kind, String topicId) {

    public enum Kind { CONTINUE, RETURN, NEW }

    public TopicRoute {
        if (kind == null) {
            throw new IllegalArgumentException("topic route kind is required");
        }
        if (kind == Kind.RETURN && (topicId == null || topicId.isBlank())) {
            throw new IllegalArgumentException("returning to a topic requires its id");
        }
        topicId = kind == Kind.RETURN ? topicId : null;
    }

    public static TopicRoute continueCurrent() {
        return new TopicRoute(Kind.CONTINUE, null);
    }

    public static TopicRoute returnTo(String topicId) {
        return new TopicRoute(Kind.RETURN, topicId);
    }

    public static TopicRoute newTopic() {
        return new TopicRoute(Kind.NEW, null);
    }

    /** 写进日志与回答属性的简短形式。 */
    public String wire() {
        return kind == Kind.RETURN ? "RETURN:" + topicId : kind.name();
    }
}
