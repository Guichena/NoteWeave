package com.noteweave.conversation;

import com.noteweave.conversation.constraint.ConstraintOpMerger;
import com.noteweave.conversation.constraint.ConstraintOpsExtractor;
import com.noteweave.conversation.constraint.ConstraintOpsPrompt;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 约束投影的唯一入口：USER 轮次交给模型抽取 ops，再交给 {@link ConstraintOpMerger} 折叠成整表快照。
 * <p>
 * 这里只有模型一条路径，没有任何关键词/正则兜底，也没有模式开关：
 * <ul>
 *   <li>模型不可用、超时、调用失败、输出不合规 —— 抽取器内部已重试一次，仍失败则该消息本轮不抽取：
 *       不产出行、只记日志。这条消息此前落库的行也不会被触碰，因为持久化侧只做 upsert，
 *       从不删除或对账（见 {@link ConversationTopicProjectionV2Service}）。</li>
 *   <li>模型明确输出空数组或 {@code NOOP} 是终局结论，同样不产出行，不会退回规则。</li>
 *   <li>重抽复用现成的重放机制：{@code refresh} 每次都会把整段会话重放一遍，
 *       本轮失败的消息在下一轮自动重抽。所以不新增「待重抽」标记表，也就不需要新的数据库迁移。</li>
 * </ul>
 * 冲突判定只在合并器里实现一次，本类不解释自然语言，也不做置信度过滤。
 */
@Service
public class ConversationConstraintProjectionV2Service {

    /** 写进 {@code rule_version}：与规则路径的 {@code constraint-projector-v2-*} 区分，也标明只有模型一条路径。 */
    public static final String VERSION = "model-only:" + ConstraintOpsPrompt.PROMPT_VERSION;

    private static final Logger log = LoggerFactory.getLogger(ConversationConstraintProjectionV2Service.class);

    private final ConstraintOpsExtractor extractor;
    private final ConstraintOpMerger merger;

    @Autowired
    public ConversationConstraintProjectionV2Service(
            ConstraintOpsExtractor extractor,
            @Value("${noteweave.context.v2.constraint-op-min-confidence:0.55}") double minConfidence
    ) {
        this(extractor, new ConstraintOpMerger(minConfidence));
    }

    /** 便于测试注入受控的合并器（例如换一个置信度门槛）。 */
    ConversationConstraintProjectionV2Service(ConstraintOpsExtractor extractor, ConstraintOpMerger merger) {
        this.extractor = extractor;
        this.merger = merger;
    }

    /** 落库时使用的 {@code rule_version} 取值。 */
    public String version() {
        return VERSION;
    }

    /**
     * 把整段会话投影成一份约束快照。
     *
     * @param messages 会话消息，按 {@code seq} 升序；助手消息会被跳过
     * @param segments 已算出的话题划分，用来给模型标出 {@code CURRENT_TOPIC} 的隔离边界
     * @return 整表快照（含 REVOKED 行），顺序等于行的创建顺序
     */
    public List<ConversationConstraintProjectorV2.Constraint> project(
            List<TopicSegmenterV2.Message> messages,
            List<TopicSegmenterV2.Segment> segments
    ) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ConstraintOpMerger.Step> steps = new ArrayList<>();
        int skipped = 0;
        for (TopicSegmenterV2.Message message : messages) {
            if (!"USER".equals(message.role())) {
                continue;
            }
            String topicId = topicIdAt(segments, message.seq());
            ConstraintOpsExtractor.Result result = extractor.extract(new ConstraintOpsExtractor.Request(
                    message.messageId(), message.seq(), message.text(), topicId,
                    merger.activeConstraints(steps)));
            if (result instanceof ConstraintOpsExtractor.Result.Ok ok) {
                steps.add(new ConstraintOpMerger.Step(message.messageId(), message.seq(), topicId, ok.ops()));
                continue;
            }
            skipped++;
            // 只打序号与原因：提示词里有用户原文，不能进日志。
            log.warn("约束抽取不可用，本轮跳过该消息: seq={}, reason={}", message.seq(), reason(result));
        }
        if (skipped > 0) {
            log.warn("约束抽取本轮跳过 {} 条消息：不产出新约束行，这些消息已落库的约束保持不变，下次重放重新抽取", skipped);
        }
        return merger.fold(steps);
    }

    private static String reason(ConstraintOpsExtractor.Result result) {
        return result instanceof ConstraintOpsExtractor.Result.Unusable unusable
                ? unusable.reason() : "NO_RESULT";
    }

    private static String topicIdAt(List<TopicSegmenterV2.Segment> segments, int seq) {
        if (segments == null) {
            return "";
        }
        for (TopicSegmenterV2.Segment segment : segments) {
            if (segment.startSeq() <= seq && seq <= segment.endSeq()) {
                return segment.topicId();
            }
        }
        return "";
    }
}
