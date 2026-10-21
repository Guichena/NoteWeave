package com.noteweave.conversation.constraint;

import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 测试用的脚本化 LLM 客户端：按序返回预置输出，不发任何网络请求。
 * <p>
 * 与仓库里 {@code TopicBoundaryJudgeTest.ScriptedClient} 同一套风格，额外支持：
 * <ul>
 *   <li>{@code "THROW"}：抛 {@link IllegalStateException}，模拟调用失败；</li>
 *   <li>{@link #sleepMillis}：在返回前睡一会儿，用来触发抽取器的超时分支。</li>
 * </ul>
 */
final class ScriptedOpsLlmClient implements ChatLlmClient {

    private static final String THROW = "THROW";

    final List<String> outputs = new ArrayList<>();
    final List<String> systemPrompts = new ArrayList<>();
    final List<String> userPrompts = new ArrayList<>();
    int calls;
    boolean enabled = true;
    long sleepMillis;

    @Override
    public String streamChat(
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            Consumer<String> onToken
    ) {
        calls++;
        systemPrompts.add(systemPrompt);
        userPrompts.add(userPrompt);
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("scripted call interrupted", ex);
            }
        }
        if (outputs.isEmpty()) {
            return "";
        }
        String output = outputs.remove(0);
        if (THROW.equals(output)) {
            throw new IllegalStateException("scripted failure");
        }
        return output;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
