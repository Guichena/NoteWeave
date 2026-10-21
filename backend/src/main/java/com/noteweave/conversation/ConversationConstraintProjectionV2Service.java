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
 *   <li>模型不可用、超时、调用失败、输出不合规 —— 抽取器内部已重试一次，仍失败则从该消息起停止本轮抽取：
 *       不产出行、只记日志，watermark 不越过它。下一次 refresh 从这条消息继续重抽，
 *       不再整段会话全量重放（见 {@link #extract}）。</li>
 *   <li>模型明确输出空数组或 {@code NOOP} 是终局结论，同样不产出行，不会退回规则；
 *       它仍然记成一步，避免下一轮把它当成没抽过的消息再调一次模型。</li>
 *   <li>增量抽取的进度（已处理的 seq 与已抽取的 ops）由 {@link ConversationTopicProjectionV2Service}
 *       持久化，模型调用因此不需要待在会话行锁事务里。</li>
 * </ul>
 * 冲突判定只在合并器里实现一次，本类不解释自然语言，也不做置信度过滤。
 */
@Service
public class ConversationConstraintProjectionV2Service {

    /** 写进 {@code rule_version}：标明抽取只有模型一条路径。 */
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
     * 把整段会话投影成一份约束快照：等价于「watermark=0、没有基线步骤」的增量抽取。
     * 离线回归与评测走这里。
     *
     * @param messages 会话消息，按 {@code seq} 升序；助手消息会被跳过
     * @param segments 已算出的话题划分，用来给模型标出 {@code CURRENT_TOPIC} 的隔离边界
     * @return 整表快照（含 REVOKED 行），顺序等于行的创建顺序
     */
    public List<ConversationConstraintProjectorV2.Constraint> project(
            List<TopicSegmenterV2.Message> messages,
            List<TopicSegmenterV2.Segment> segments
    ) {
        return extract(messages, segments, 0, List.of()).snapshot();
    }

    /**
     * 增量抽取：只对 {@code seq > watermarkSeq} 的 USER 消息调用模型，再和基线步骤一起折叠成整表快照。
     * <p>
     * 基线步骤是此前几轮已经抽取过的 ops（落库在 {@code conversation_constraint_state_v2.steps_json}），
     * 只有它们能让合并器在跨轮次时继续判断「同槽位新值顶掉旧值」「按 targetId 撤回」。
     * <p>
     * 抽取不可用时从该消息起停住而不是跳过它：watermark 停在失败消息之前，
     * 下一次 refresh 会重抽这条消息，不会把没抽到的消息永久漏掉，也不会整段重放。
     *
     * @param messages      会话消息，按 {@code seq} 升序；助手消息与已处理过的消息会被跳过
     * @param segments      已算出的话题划分
     * @param watermarkSeq  已经处理到的消息 seq；调用方保证它之前的所有 USER 消息都已抽取
     * @param baselineSteps 已经抽取过的步骤，按会话顺序；可为空
     * @return 本轮新增的步骤、新的 watermark 与整表快照
     */
    public Extraction extract(
            List<TopicSegmenterV2.Message> messages,
            List<TopicSegmenterV2.Segment> segments,
            int watermarkSeq,
            List<ConstraintOpMerger.Step> baselineSteps
    ) {
        int processedSeq = Math.max(0, watermarkSeq);
        List<ConstraintOpMerger.Step> all = new ArrayList<>();
        if (baselineSteps != null) {
            for (ConstraintOpMerger.Step step : baselineSteps) {
                all.add(withCurrentTopic(step, segments));
            }
        }
        if (messages == null || messages.isEmpty()) {
            return new Extraction(List.of(), processedSeq, merger.fold(all), 0, 0);
        }
        List<ConstraintOpMerger.Step> added = new ArrayList<>();
        int extracted = 0;
        int skipped = 0;
        for (TopicSegmenterV2.Message message : messages) {
            if (!"USER".equals(message.role()) || message.seq() <= processedSeq) {
                continue;
            }
            String topicId = topicIdAt(segments, message.seq());
            ConstraintOpsExtractor.Result result = extractor.extract(new ConstraintOpsExtractor.Request(
                    message.messageId(), message.seq(), message.text(), topicId,
                    merger.activeConstraints(all)));
            if (result instanceof ConstraintOpsExtractor.Result.Ok ok) {
                ConstraintOpMerger.Step step =
                        new ConstraintOpMerger.Step(message.messageId(), message.seq(), topicId, ok.ops());
                all.add(step);
                added.add(step);
                processedSeq = message.seq();
                extracted++;
                continue;
            }
            skipped++;
            // 只打序号与原因：提示词里有用户原文，不能进日志。
            log.warn("约束抽取不可用，本轮从该消息起停止: seq={}, reason={}", message.seq(), reason(result));
            break;
        }
        if (skipped > 0) {
            log.warn("约束抽取本轮停在 seq={}：该消息此前落库的约束保持不变，下次从这条消息重抽", processedSeq);
        }
        return new Extraction(List.copyOf(added), processedSeq, merger.fold(all), extracted, skipped);
    }

    /**
     * 冲突重放：用库里最新的基线重新折叠本轮已经抽取到的 ops，<b>不再调用模型</b>。
     * <p>
     * 乐观检查失败说明抽取期间另一个 refresh 先提交了，它的 watermark {@code watermarkSeq}
     * 已经覆盖了双方重复处理过的消息。这里只保留 seq 在其之后的步骤重新折叠，
     * 结果与「从最新基线继续增量抽取」一致，但不会像 {@link #extract} 那样再调一次模型。
     *
     * @param segments       当前切分，用来像 {@link #extract} 一样按最新话题重算基线步骤的 topicId
     * @param watermarkSeq   库里最新的已处理水位
     * @param baselineSteps  库里最新的基线步骤
     * @param extractedSteps 本轮抽取到的步骤
     * @return 重放结果：{@code steps} 只含水位之后仍需落库的步骤，为空表示胜者已覆盖本轮全部消息
     */
    Extraction refold(List<TopicSegmenterV2.Segment> segments, int watermarkSeq,
                      List<ConstraintOpMerger.Step> baselineSteps,
                      List<ConstraintOpMerger.Step> extractedSteps) {
        List<ConstraintOpMerger.Step> all = new ArrayList<>();
        if (baselineSteps != null) {
            for (ConstraintOpMerger.Step step : baselineSteps) {
                all.add(withCurrentTopic(step, segments));
            }
        }
        List<ConstraintOpMerger.Step> kept = new ArrayList<>();
        if (extractedSteps != null) {
            for (ConstraintOpMerger.Step step : extractedSteps) {
                if (step.seq() > watermarkSeq) {
                    all.add(step);
                    kept.add(step);
                }
            }
        }
        int processedSeq = Math.max(0, watermarkSeq);
        for (ConstraintOpMerger.Step step : kept) {
            processedSeq = Math.max(processedSeq, step.seq());
        }
        return new Extraction(List.copyOf(kept), processedSeq, merger.fold(all), kept.size(), 0);
    }

    /**
     * 一次增量抽取的结果。
     *
     * @param steps        本轮新增的步骤（只含新消息，按会话顺序）
     * @param processedSeq 本轮处理到的 USER 消息 seq；失败处停住，下一轮从这里重抽
     * @param snapshot     基线 + 新步骤折叠出的整表快照
     * @param extracted    本轮成功抽取的消息条数
     * @param skipped      本轮因抽取不可用而停住的消息条数（0 或 1）
     */
    public record Extraction(List<ConstraintOpMerger.Step> steps, int processedSeq,
                             List<ConversationConstraintProjectorV2.Constraint> snapshot,
                             int extracted, int skipped) {

        public Extraction {
            steps = steps == null ? List.of() : List.copyOf(steps);
            snapshot = snapshot == null ? List.of() : List.copyOf(snapshot);
        }
    }

    private static String reason(ConstraintOpsExtractor.Result result) {
        return result instanceof ConstraintOpsExtractor.Result.Unusable unusable
                ? unusable.reason() : "NO_RESULT";
    }

    /**
     * 话题边界复核可能把已抽取的消息归到别的话题。基线步骤按当前切分重算 {@code topicId}，
     * 让 {@code CURRENT_TOPIC} 的隔离键与整段重放保持一致。
     */
    private static ConstraintOpMerger.Step withCurrentTopic(ConstraintOpMerger.Step step,
                                                           List<TopicSegmenterV2.Segment> segments) {
        String topicId = topicIdAt(segments, step.seq());
        if (topicId.isEmpty() || topicId.equals(step.topicId())) {
            return step;
        }
        return new ConstraintOpMerger.Step(step.messageId(), step.seq(), topicId, step.ops());
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
