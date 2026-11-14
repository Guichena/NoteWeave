package com.noteweave.conversation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 冻结上下文之前，为查询改写模型准备判断话题所需的材料：最近几条原文，以及本会话已有话题的列表。
 * <p>
 * 话题列表按最近活跃排序，T1 是最近在聊的话题；每个话题给出一行标题，最近的几个话题再附上摘要或首尾提问，
 * 让模型能判断省略主语的追问接的是哪个话题，回到较早的话题时也能补全被省略的主题。
 * 只读已经落库的话题段与摘要，不调用模型，也不持锁。
 */
@Service
public class ConversationTopicRoutingService {

    /** 原文窗口的条数，与冻结时原文尾部的上限一致。 */
    static final int RECENT_MESSAGES = 8;
    /** 原文窗口里每条消息的字数上限：只用于判断话题与补全指代。 */
    static final int RECENT_MESSAGE_CHARS = 600;
    /** 列出的话题数上限。 */
    static final int MAX_TOPICS = 8;
    /** 附上摘要的话题数（按最近活跃）。 */
    static final int DETAILED_TOPICS = 3;
    static final int TITLE_CHARS = 36;
    static final int SUMMARY_CHARS = 400;
    static final int QUESTION_CHARS = 80;

    private final JdbcTemplate jdbc;

    public ConversationTopicRoutingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RoutingContext load(String workspaceId, String conversationId, String currentMessageId) {
        Integer cutoff = jdbc.query("""
                select message_seq from conversation_message
                where id = ? and workspace_id = ? and conversation_id = ?
                """, rs -> rs.next() ? rs.getInt(1) : null, currentMessageId, workspaceId, conversationId);
        if (cutoff == null) {
            return RoutingContext.EMPTY;
        }
        List<RecentMessage> recent = new ArrayList<>(jdbc.query("""
                select message_seq, role, content from conversation_message
                where workspace_id = ? and conversation_id = ? and message_seq < ?
                  and context_status = 'CURRENT' and role in ('USER', 'ASSISTANT')
                order by message_seq desc limit ?
                """, (rs, index) -> new RecentMessage(rs.getInt(1), rs.getString(2),
                bound(rs.getString(3), RECENT_MESSAGE_CHARS)),
                workspaceId, conversationId, cutoff, RECENT_MESSAGES));
        recent.sort(Comparator.comparingInt(RecentMessage::seq));
        List<SegmentRow> segments = jdbc.query("""
                select topic_id, start_seq, end_seq from conversation_topic_segment_v2
                where workspace_id = ? and conversation_id = ? and decision_status <> 'STALE'
                  and start_seq < ?
                order by start_seq
                """, (rs, index) -> new SegmentRow(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                workspaceId, conversationId, cutoff);
        if (segments.isEmpty()) {
            return new RoutingContext(cutoff, recent, List.of(), "");
        }
        Map<String, TopicSpan> spans = new LinkedHashMap<>();
        for (SegmentRow segment : segments) {
            int end = Math.min(segment.endSeq(), cutoff - 1);
            spans.merge(segment.topicId(), new TopicSpan(segment.startSeq(), end),
                    (left, right) -> new TopicSpan(Math.min(left.firstSeq(), right.firstSeq()),
                            Math.max(left.lastSeq(), right.lastSeq())));
        }
        Map<String, String> summaryByTopic = new HashMap<>();
        Map<String, Integer> summaryEnd = new HashMap<>();
        jdbc.query("""
                select topic_id, end_seq, summary_text from conversation_topic_summary_revision_v2
                where workspace_id = ? and conversation_id = ? and status = 'READY' and end_seq < ?
                """, rs -> {
            String topicId = rs.getString(1);
            int end = rs.getInt(2);
            if (end > summaryEnd.getOrDefault(topicId, 0)) {
                summaryEnd.put(topicId, end);
                summaryByTopic.put(topicId, rs.getString(3));
            }
        }, workspaceId, conversationId, cutoff);
        List<Map.Entry<String, TopicSpan>> ordered = new ArrayList<>(spans.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, TopicSpan> entry) -> entry.getValue().lastSeq())
                .reversed());
        if (ordered.size() > MAX_TOPICS) {
            ordered = ordered.subList(0, MAX_TOPICS);
        }
        Map<Integer, String> userQuestions = userQuestions(workspaceId, conversationId, cutoff);
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            String topicId = ordered.get(index).getKey();
            TopicSpan span = ordered.get(index).getValue();
            String firstQuestion = firstQuestion(userQuestions, span.firstSeq(), span.lastSeq(), true);
            String lastQuestion = firstQuestion(userQuestions, span.firstSeq(), span.lastSeq(), false);
            String summary = summaryByTopic.getOrDefault(topicId, "");
            String title = bound(firstLine(summary.isBlank() ? firstQuestion : summary), TITLE_CHARS);
            String gist = "";
            if (index < DETAILED_TOPICS) {
                gist = summary.isBlank()
                        ? "首问：" + bound(firstQuestion, QUESTION_CHARS)
                        + (lastQuestion.equals(firstQuestion) ? "" : "；最近一问：" + bound(lastQuestion, QUESTION_CHARS))
                        : "摘要：" + bound(summary.replace('\n', ' '), SUMMARY_CHARS);
            }
            candidates.add(new Candidate("T" + (index + 1), topicId, title, gist, span.lastSeq()));
        }
        String currentSummary = summaryByTopic.getOrDefault(candidates.get(0).topicId(), "");
        return new RoutingContext(cutoff, recent, candidates, currentSummary);
    }

    private Map<Integer, String> userQuestions(String workspaceId, String conversationId, int cutoff) {
        Map<Integer, String> questions = new HashMap<>();
        jdbc.query("""
                select message_seq, content from conversation_message
                where workspace_id = ? and conversation_id = ? and message_seq < ?
                  and role = 'USER' and context_status = 'CURRENT'
                """, rs -> {
            questions.put(rs.getInt(1), rs.getString(2));
        }, workspaceId, conversationId, cutoff);
        return questions;
    }

    private static String firstQuestion(Map<Integer, String> questions, int from, int to, boolean earliest) {
        if (earliest) {
            for (int seq = from; seq <= to; seq++) {
                String text = questions.get(seq);
                if (text != null && !text.isBlank()) return text.trim();
            }
        } else {
            for (int seq = to; seq >= from; seq--) {
                String text = questions.get(seq);
                if (text != null && !text.isBlank()) return text.trim();
            }
        }
        return "";
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        for (String separator : List.of("\n", "。", "；")) {
            int index = trimmed.indexOf(separator);
            if (index > 0) trimmed = trimmed.substring(0, index);
        }
        return trimmed.replaceAll("^[#\\-*\\s]+", "").trim();
    }

    static String bound(String value, int maximum) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maximum ? trimmed : trimmed.substring(0, maximum) + "…";
    }

    public record RecentMessage(int seq, String role, String text) {
    }

    public record Candidate(String alias, String topicId, String title, String gist, int lastSeq) {
    }

    /**
     * @param candidates 按最近活跃排序的话题，第一个（T1）是最近在聊的话题
     * @param currentTopicSummary 最近话题的最新摘要，没有时为空
     */
    public record RoutingContext(int cutoffSeq, List<RecentMessage> recent, List<Candidate> candidates,
                                 String currentTopicSummary) {
        public static final RoutingContext EMPTY = new RoutingContext(0, List.of(), List.of(), "");

        public RoutingContext {
            recent = List.copyOf(recent);
            candidates = List.copyOf(candidates);
            currentTopicSummary = currentTopicSummary == null ? "" : currentTopicSummary;
        }

        public boolean hasHistory() {
            return !recent.isEmpty();
        }

        /** 交给改写模型的话题列表；没有话题时返回空串。 */
        public String renderTopics() {
            if (candidates.isEmpty()) return "";
            StringBuilder text = new StringBuilder();
            for (Candidate candidate : candidates) {
                text.append(candidate.alias());
                if (candidate == candidates.get(0)) text.append("（最近在聊）");
                text.append("：").append(candidate.title().isBlank() ? "（无标题）" : candidate.title()).append('\n');
                if (!candidate.gist().isBlank()) text.append("  ").append(candidate.gist()).append('\n');
            }
            return text.toString().trim();
        }

        /**
         * 把模型输出的话题编号换成话题判断：T1 表示接着最近的话题，其他编号表示回到那个话题，n 表示新话题。
         * 编号不在列表里或输出为空时返回 null，交给规则判断。
         */
        public TopicRoute resolve(String token) {
            if (token == null || token.isBlank() || candidates.isEmpty()) return null;
            String normalized = token.trim();
            if ("n".equalsIgnoreCase(normalized) || "new".equalsIgnoreCase(normalized)) return TopicRoute.newTopic();
            for (Candidate candidate : candidates) {
                if (candidate.alias().equalsIgnoreCase(normalized)) {
                    return candidate == candidates.get(0)
                            ? TopicRoute.continueCurrent() : TopicRoute.returnTo(candidate.topicId());
                }
            }
            return null;
        }
    }

    private record SegmentRow(String topicId, int startSeq, int endSeq) {
    }

    private record TopicSpan(int firstSeq, int lastSeq) {
    }
}
