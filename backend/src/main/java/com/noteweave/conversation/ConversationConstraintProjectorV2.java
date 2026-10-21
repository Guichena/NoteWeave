package com.noteweave.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Shadow extraction of explicit user instructions; no assistant text becomes a constraint. */
public final class ConversationConstraintProjectorV2 {
    public static final String RULE_VERSION = "constraint-projector-v2-a6";
    /** 语言只有一个语义槽位：一次回答只用一种语言，"用英文"和"用中文"互斥。 */
    static final String SLOT_LANGUAGE = "answer.language";
    /**
     * 长度类格式线索拆成两个维度：字数上限和详略，维度内互斥、维度之间可以同时生效。
     * 槽位只在投影内部使用，不入库。
     */
    static final String SLOT_LENGTH_MAX = "format.length.max";
    static final String SLOT_LENGTH_DETAIL = "format.length.detail";
    /** 会话级 scope：比当前话题更宽，同值重申不能把它降级。 */
    private static final String SCOPE_CONVERSATION = "CONVERSATION";
    /** 没有指向具体线索的否定要求（"不要再贴大段配置"）单独占一个槽位，不撤回任何旧约束。 */
    private static final String SLOT_NEGATION_PREFIX = "negation:";
    private static final String SLOT_UNRESOLVED_PREFIX = "unresolved:";
    /** 长期生效的措辞。 */
    private static final java.util.regex.Pattern DURABLE = java.util.regex.Pattern.compile(
            "以后|今后|往后|之后都|从现在起|从今以后|每次|每一次|一律|始终|总是|一直都?要");
    /**
     * 显式"仅本话题"措辞：用户明说这条要求只管这一次/这个话题，即使同一句里还有长期措辞，
     * 也只算当前话题的约束（"以后都用表格，这次用分点"里的分点不升级成会话级）。
     */
    private static final java.util.regex.Pattern NARROW = java.util.regex.Pattern.compile(
            "这次|本次|这一次|这轮|本轮|本话题|这个话题|这个问题|临时|暂时");
    /** 语言名字面量；否定判定复用同一份词表，避免两处清单漂移。 */
    private static final String LANGUAGE_NAME = "简体中文|繁体中文|中文|英文|英语|日文|日语|韩文";
    private static final java.util.regex.Pattern LANGUAGE = java.util.regex.Pattern.compile(
            "(?:改用|改成|换成|使用|用)(" + LANGUAGE_NAME + ")");
    /** 不带"用"的语言名（"不是英文"）。 */
    private static final java.util.regex.Pattern LANGUAGE_WORD = java.util.regex.Pattern.compile(LANGUAGE_NAME);
    /** 否定也要能指向的长度目标（"别太长""不要再贴大段配置"）；这些词单独出现是抱怨，不是正向格式要求。 */
    private static final java.util.regex.Pattern LENGTH_WORD = java.util.regex.Pattern.compile(
            "太长|过长|大段|长篇|大篇幅|整段|一大堆|一堆|废话|啰嗦|冗长");
    private static final java.util.regex.Pattern FORMAT_CUE = java.util.regex.Pattern.compile(
            "先给结论|结论先行|先说结论|用表格|表格对比|分点|分条|列表|列出|不超过\\d+字|控制在\\d+字|"
                    + "字数|简短|简洁|精简|详细一点|展开讲|附上(?:来源|引用|出处)|标注(?:来源|出处)|带上代码|给出代码");
    /**
     * 否定词。单独一个"别"只在不是别的词的语素时才是否定（"别太长"是要求，"特别/区别/别人/别的"不是）。
     */
    private static final java.util.regex.Pattern NEGATION = java.util.regex.Pattern.compile(
            "不要|别再|不许|不是|不用|禁止|避免|(?<![特区告级性分辨差识派类离个干作道])别(?![人处的些])");
    /** 否定词后面最多隔几个字符就必须出现目标，超过这个距离的否定只是语气。 */
    private static final int NEGATION_TARGET_WINDOW = 8;
    /**
     * 紧贴在语言/格式线索前面、并落在同一分句内的否定前缀。
     * 除"不要""别""不是""不用""不许""禁止""避免""不"外，允许夹一个紧邻的副词或动词（"不要再用""禁止使用"），
     * 但"不要忘记用英文"这类中间隔着无关成分的说法仍然是正向要求。
     */
    private static final java.util.regex.Pattern NEGATION_PREFIX = java.util.regex.Pattern.compile(
            "(?:不要|别|不是|不用|不许|禁止|避免|不)(?:再|总是|一直)?(?:使用|用|使)?$");
    /**
     * 显式更正措辞：用户明说要改掉之前的说法时，允许撤回**跨 scope** 的同槽位旧约束
     * （"以后用英文"之后说"改用中文"，会话级英文偏好必须失效）。
     * 没有这些词时，另一个 scope 里的新取值只是局部补充，不动会话级偏好。
     * 单独的"换"不算，避免"换个话题，用表格列出……"被当成更正。
     * "不是""不用""不要"不在这里：它们单独出现只是语气（"这不是重点""不用了"），
     * 出现在别的分句里也不修饰本条线索（"这个不要，用中文解释一下"），整句匹配会把
     * "以后用英文"降级成当前话题；只有构成约束形态的否定（"不用英文""不要用英文"）
     * 才算更正，见 {@link #isCorrection}。
     */
    private static final java.util.regex.Pattern EXPLICIT_CORRECTION = java.util.regex.Pattern.compile(
            "更正|纠正|改正|改用|改成|改为|换用|换成|换为|别再|禁止|避免|取消|去掉|撤销|替换");
    /** 分句边界：否定只作用于它所在的分句。 */
    private static final String CLAUSE_BREAK = "，,。；;！!？?\n：:";
    /** "那个格式""跟之前一样"这类没有说清指什么的指代。 */
    private static final java.util.regex.Pattern VAGUE_REFERENCE = java.util.regex.Pattern.compile(
            "那个格式|那种格式|之前那样|上次那样|跟之前一样|和之前一样|原来那样|照旧");
    private static final java.util.regex.Pattern LEADING_FILLER = java.util.regex.Pattern.compile(
            "^(?:更正|补充|另外|还有|请|记住|记得|以后|今后|往后|从现在起|从今以后|每次|一律|始终|总是)+[：:，,\\s]*");

    public List<Constraint> project(List<TopicSegmenterV2.Message> messages) {
        List<Constraint> output = new ArrayList<>();
        List<Active> active = new ArrayList<>();
        List<TopicSegmenterV2.Segment> segments = new TopicSegmenterV2().segment(messages).segments();
        for (TopicSegmenterV2.Message message : messages) {
            if (!"USER".equals(message.role())) continue;
            String text = message.text().trim();
            Detected detected = explicit(message, text);
            if (detected == null) continue;
            Constraint next = detected.constraint();
            if ("UNRESOLVED".equals(next.status())) {
                // 指代不明没有取值：既不撤回旧约束，也不会被后续约束撤回。
                output.add(next);
                continue;
            }
            String topicId = segments.stream()
                    .filter(segment -> segment.startSeq() <= message.seq()
                            && message.seq() <= segment.endSeq())
                    .findFirst().map(TopicSegmenterV2.Segment::topicId).orElse("");
            String scopeKey = "CURRENT_TOPIC".equals(next.scope())
                    ? next.scope() + ":" + topicId
                    : next.scope();
            boolean correction = isCorrection(text, detected);
            // 用户明说"这次/仅本话题"时作用范围就限在当前话题：既不能升级成会话级，
            // 也不能借"更正/改用"之类的措辞撤回会话级偏好（"以后用英文，这次改用中文"）。
            boolean narrowed = detected.narrowed();
            // 撤回按语义槽位（语言 / 表格 / 结论 / 分点 / 字数上限 / 详略 / 引用 / 代码）而不是按 kind：
            // 同一槽位里的取值互斥，"用表格"和"先给结论"这类互补格式可以同时生效。
            // 同 scope 内出现新的 ACTIVE 取值时，上一条立即失效，不要求用户说出关键词。
            // 跨 scope 只有用户明说"更正/改用/换成"或构成约束形态的否定才撤回，否则一个话题里的临时要求
            // 会悄悄废掉会话级偏好。否定约束只撤回同一取值的旧约束：
            // "不要用中文"不会撤掉"以后用英文"，而"不要用英文"会。
            // 同槽位同取值的正向重申只是确认，保留已有（更宽）的约束，不新建更窄的约束；
            // 但确认不能就此结束扫描：更老的同槽位冲突行仍要撤回，否则"用中文"→"以后用英文"
            // →"继续用英文"会留下话题级"中文"，当前话题同时要求中英文。
            String confirmedBy = null;
            List<Active> superseded = new ArrayList<>();
            for (int index = active.size() - 1; index >= 0; index--) {
                Active previous = active.get(index);
                if (!previous.slot().equals(detected.slot())) continue;
                if ((previous.forbid() || detected.forbid())
                        && previous.value() != null && detected.value() != null
                        && !previous.value().equals(detected.value())) continue;
                if (confirms(previous, detected, scopeKey)) {
                    // 确认行只保留最新的一条（和原先 break 的效果一致），撤回行指向它。
                    if (confirmedBy == null) confirmedBy = output.get(previous.index()).id();
                    continue;
                }
                if ((narrowed || !correction) && !previous.scopeKey().equals(scopeKey)) continue;
                superseded.add(previous);
            }
            for (Active previous : superseded) {
                Constraint old = output.get(previous.index());
                output.set(previous.index(), new Constraint(old.id(), old.sourceMessageId(), old.kind(),
                        old.scope(), old.text(), old.validFromSeq(), message.seq(),
                        "REVOKED", confirmedBy == null ? next.id() : confirmedBy));
                active.remove(previous);
            }
            if (confirmedBy != null) continue;
            output.add(next);
            active.add(new Active(output.size() - 1, detected.slot(), detected.value(),
                    detected.forbid(), scopeKey));
        }
        return List.copyOf(output);
    }

    /**
     * 识别一条用户消息里的显式要求，每条消息最多产出一条约束，优先级：
     * 指代不明 > （未被否定的）语言 > （未被否定的）格式 > 约束形态的否定。
     * 否定先于语言和格式生效："不是用英文""禁止用表格"里的线索是被拒绝的取值，不能当成正向要求；
     * 只有否定前缀和线索同处一个分句时才算否定，"不要用英文，用中文写"里的"用中文"仍然生效。
     * 否定还必须是约束形态：否定词后面跟着语言/格式/长度目标（"不要用英文""不用表格""别太长"）。
     * 只有否定语气、没有目标的"不是""不用"（"这不是我要的""不用了""不是这个意思"）不产生约束。
     * 否定约束继承被否定线索的槽位，这样"不要用表格"才能撤回之前的"用表格"。
     * 带"以后""每次""从现在起"等长期措辞的要求在整个会话内生效，否则只在当前话题内生效；
     * 明说"这次""仅本话题"的要求优先按当前话题生效，长期措辞不能把它升级成会话级。
     */
    private Detected explicit(TopicSegmenterV2.Message message, String text) {
        String kind;
        String ruleText;
        String status = "ACTIVE";
        String slot;
        String value = null;
        boolean forbid = false;
        Cue negatedCue = firstNegatedCue(text);
        java.util.regex.Matcher language;
        java.util.regex.Matcher format;
        java.util.regex.Matcher negation;
        if (VAGUE_REFERENCE.matcher(text).find()) {
            // 指向前文某种格式但没有说清是哪种，不能直接生效
            kind = "FORMAT";
            ruleText = "";
            status = "UNRESOLVED";
            slot = SLOT_UNRESOLVED_PREFIX + message.messageId();
        } else if ((language = lastPositiveLanguage(text)) != null) {
            kind = "LANGUAGE";
            ruleText = language.group(1);
            slot = SLOT_LANGUAGE;
            value = languageValue(ruleText);
        } else if ((format = firstPositiveFormat(text)) != null) {
            kind = "FORMAT";
            ruleText = clause(text, format.start());
            slot = formatSlot(format.group());
            value = slot;
        } else if ((negation = negationWithTarget(text)) != null) {
            kind = "NEGATION";
            ruleText = clause(text, negation.start());
            forbid = true;
            slot = negatedCue == null ? SLOT_NEGATION_PREFIX + message.messageId() : negatedCue.slot();
            value = negatedCue == null ? null : negatedCue.value();
        } else if (negatedCue != null) {
            // 唯一的语言/格式线索被否定了，这条要求只能记为否定约束
            kind = "NEGATION";
            ruleText = clause(text, negatedCue.position());
            forbid = true;
            slot = negatedCue.slot();
            value = negatedCue.value();
        } else {
            return null;
        }
        boolean narrowed = NARROW.matcher(text).find();
        String scope = narrowed || !DURABLE.matcher(text).find() ? "CURRENT_TOPIC" : "CONVERSATION";
        String id = "constraint-" + digest(message.messageId() + ":" + kind).substring(0, 24);
        return new Detected(new Constraint(id, message.messageId(), kind, scope, ruleText,
                message.seq(), null, status, null), slot, value, forbid, narrowed);
    }

    /**
     * 这条消息是否在更正之前的说法。除显式更正词外，与目标槽位构成约束形态的否定
     * （"不用英文""不是用表格"）也算更正；单独的语气词（"这不是重点""不用了"）不算，
     * 否则一句口语就能把会话级偏好降级成当前话题。"不要"同样不进整句词表：只有它紧贴目标、
     * 让本条消息本身成为否定约束时才算更正，"这个不要，用中文解释一下"里的"不要"在另一个分句。
     */
    private static boolean isCorrection(String text, Detected detected) {
        if (EXPLICIT_CORRECTION.matcher(text).find()) return true;
        return detected.forbid() && detected.value() != null;
    }

    /**
     * 同槽位、同取值的正向重申/确认。命中时保留这一行（包括更宽的 CONVERSATION scope），
     * 也不新建更窄的约束："以后用英文"之后的"这不是重点，继续用英文"
     * 只是确认，不能把会话级偏好降级成当前话题；同 scope 内的同值重申也保持幂等。
     * 只有取值不同，或用户明说"更正/改用"时才覆盖旧约束。
     * 确认行本身不撤回，但调用方仍会继续扫描更老的同槽位冲突行。
     */
    private static boolean confirms(Active previous, Detected detected, String scopeKey) {
        if (previous.forbid() || detected.forbid()) return false;
        if (!hasDistinctValue(detected.slot())) return false;
        if (previous.value() == null || !previous.value().equals(detected.value())) return false;
        return previous.scopeKey().equals(scopeKey) || SCOPE_CONVERSATION.equals(previous.scopeKey());
    }

    /**
     * 槽位是否有独立取值。语言槽位的取值是"英文/中文"，格式槽位的取值就是槽位本身
     * （"简短一点"和"详细一点"都落在 {@value #SLOT_LENGTH_DETAIL}），同值判断对格式槽位没有意义。
     */
    private static boolean hasDistinctValue(String slot) {
        return SLOT_LANGUAGE.equals(slot);
    }

    /** 最后一处没有被"不要""别""不是""不用""禁止""避免""不"否定的"用某种语言"。 */
    private static java.util.regex.Matcher lastPositiveLanguage(String text) {
        java.util.regex.Matcher matcher = LANGUAGE.matcher(text);
        java.util.regex.Matcher found = null;
        while (matcher.find()) {
            if (negated(text, matcher.start())) continue;
            found = LANGUAGE.matcher(text);
            found.find(matcher.start());
        }
        return found;
    }

    /** 第一处没有被否定前缀否定的格式线索。 */
    private static java.util.regex.Matcher firstPositiveFormat(String text) {
        java.util.regex.Matcher matcher = FORMAT_CUE.matcher(text);
        while (matcher.find()) {
            if (!negated(text, matcher.start())) return matcher;
        }
        return null;
    }

    /** 第一处被否定的语言/格式线索，带上它所属的槽位和取值，没有则返回 null。 */
    private static Cue firstNegatedCue(String text) {
        java.util.regex.Matcher language = LANGUAGE.matcher(text);
        while (language.find()) {
            if (negated(text, language.start())) {
                return new Cue(language.start(), SLOT_LANGUAGE, languageValue(language.group(1)));
            }
        }
        java.util.regex.Matcher format = FORMAT_CUE.matcher(text);
        while (format.find()) {
            if (negated(text, format.start())) {
                // 格式槽位里的取值就是槽位本身：正向的"用表格"和被否定的"不要用表格"必须能对上，
                // 否则"不要用表格"撤不掉之前的"用表格"。
                String slot = formatSlot(format.group());
                return new Cue(format.start(), slot, slot);
            }
        }
        return null;
    }

    /**
     * 约束形态的否定：否定词后面同一分句内还要跟着语言/格式/长度目标。
     * "不要用英文""不用表格""别太长""不是用英文"是要求；
     * "这不是我要的""不用了""不是这个意思"只有否定语气，没有可约束的目标，不产生约束。
     */
    private static java.util.regex.Matcher negationWithTarget(String text) {
        java.util.regex.Matcher matcher = NEGATION.matcher(text);
        while (matcher.find()) {
            if (hasNegationTarget(text, matcher.start(), matcher.end())) return matcher;
        }
        return null;
    }

    /** 否定词后面 {@value #NEGATION_TARGET_WINDOW} 个字符以内（不得跨分句）是否出现语言/格式/长度目标。 */
    private static boolean hasNegationTarget(String text, int negatorStart, int negatorEnd) {
        int end = negatorEnd;
        while (end < text.length() && end - negatorEnd < NEGATION_TARGET_WINDOW
                && CLAUSE_BREAK.indexOf(text.charAt(end)) < 0) {
            end++;
        }
        String window = text.substring(negatorStart, end);
        return LANGUAGE.matcher(window).find()
                || LANGUAGE_WORD.matcher(window).find()
                || FORMAT_CUE.matcher(window).find()
                || LENGTH_WORD.matcher(window).find();
    }

    /**
     * 格式线索所属的语义槽位：同一槽位里的取值互斥，不同槽位可以同时生效。
     * 长度类线索不再和其他格式共用一个兜底桶，而是按维度拆开：
     * "不超过300字/控制在N字/字数"是硬上限，落在 {@value #SLOT_LENGTH_MAX}；
     * "简短/简洁/精简"与"详细一点/展开讲"是同一个详略维度里互斥的两个取值，
     * 落在 {@value #SLOT_LENGTH_DETAIL}。因此"不超过300字"和"简短一点"可以同时生效，
     * 而"简短一点"之后的"详细一点"仍然顶掉它。
     */
    static String formatSlot(String cue) {
        if (cue.contains("表格")) return "format.table";
        if (cue.contains("结论")) return "format.conclusion";
        if (cue.contains("分点") || cue.contains("分条") || cue.contains("列表") || cue.contains("列出")) {
            return "format.bullets";
        }
        if (cue.contains("代码")) return "format.code";
        if (cue.contains("来源") || cue.contains("引用") || cue.contains("出处")) return "format.citation";
        if (cue.contains("不超过") || cue.contains("控制在") || cue.contains("字数")) return SLOT_LENGTH_MAX;
        if (cue.contains("简短") || cue.contains("简洁") || cue.contains("精简")
                || cue.contains("详细") || cue.contains("展开")) {
            return SLOT_LENGTH_DETAIL;
        }
        // 兜底只接未登记的线索，当前 FORMAT_CUE 里的每条线索都有专属槽位。
        return "format.length";
    }

    /** 同一门语言的不同叫法归一化，"英语"和"英文"是同一个取值。 */
    private static String languageValue(String cue) {
        return switch (cue) {
            case "英语" -> "英文";
            case "日语" -> "日文";
            default -> cue;
        };
    }

    /** 命中位置前面（同一分句内）是否紧跟着否定前缀。 */
    private static boolean negated(String text, int position) {
        int start = position;
        while (start > 0 && CLAUSE_BREAK.indexOf(text.charAt(start - 1)) < 0) start--;
        return NEGATION_PREFIX.matcher(text.substring(start, position).stripTrailing()).find();
    }

    /** 命中位置所在的分句，去掉开头的长期措辞和"请""记住"等客套，作为约束原文。 */
    private static String clause(String text, int position) {
        int start = position;
        while (start > 0 && CLAUSE_BREAK.indexOf(text.charAt(start - 1)) < 0) start--;
        int end = position;
        while (end < text.length() && "，,。；;！!？?\n".indexOf(text.charAt(end)) < 0) end++;
        String clause = text.substring(start, end).trim();
        return LEADING_FILLER.matcher(clause).replaceFirst("").trim();
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** 被否定的语言/格式线索：位置、所属槽位和取值。 */
    private record Cue(int position, String slot, String value) {}

    /** 一条消息抽出的约束，以及只在本类内部使用的槽位/取值/是否否定/是否显式窄化到本话题。 */
    private record Detected(Constraint constraint, String slot, String value, boolean forbid,
                            boolean narrowed) {}

    /** 当前仍然生效的约束，槽位和 scope 用来判断后续消息能否撤回它。 */
    private record Active(int index, String slot, String value, boolean forbid, String scopeKey) {}

    public record Constraint(String id, String sourceMessageId, String kind, String scope,
                             String text, int validFromSeq, Integer invalidAfterSeq,
                             String status, String supersededBy) {}
}
