package com.noteweave.chat;

import java.util.List;
import java.util.function.Consumer;

/**
 * 统一聊天链路的 LLM 流式调用接口。
 * <p>
 * 由 ChatService.stream 在服务端 SSE 输出过程中按 token 消费，
 * 把模型生成内容转成 {@code answer.delta} 事件推给前端。
 */
public interface ChatLlmClient {

    /**
     * 同步等待一次完整的流式生成；每收到一段 token 文本就回调 {@code onToken}。
     * 整个流结束后方法返回，{@code onToken} 不再被调用。
     *
     * @param systemPrompt  系统提示词，描述模型角色、风格与禁用路径
     * @param userPrompt    用户提示词，包含原问题与资料证据上下文
     * @param onToken       每个 token 文本片段的回调
     * @return 拼接后的完整模型输出
     */
    default String streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        return streamChat(systemPrompt, userPrompt, 1200, onToken);
    }

    String streamChat(
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            Consumer<String> onToken
    );

    /**
     * 多轮上下文形式，与 {@link #streamChat(String, String, Consumer)} 等价。
     * 默认实现把 messages 折叠成 system + user 两段。
     */
    default String streamChat(List<ChatMessage> messages, Consumer<String> onToken) {
        StringBuilder system = new StringBuilder();
        StringBuilder user = new StringBuilder();
        for (ChatMessage message : messages) {
            switch (message.role()) {
                case SYSTEM -> system.append(message.content()).append("\n\n");
                case USER -> user.append(message.content()).append("\n\n");
                case ASSISTANT -> user.append("[上一轮助手] ").append(message.content()).append("\n\n");
            }
        }
        return streamChat(system.toString().trim(), user.toString().trim(), onToken);
    }

    /**
     * 当前 LLM 是否处于可调用状态；用于在 ChatService.stream 内部决定走真流式还是回退到模板 chunk。
     */
    boolean isEnabled();

    record ChatMessage(Role role, String content) {
    }

    enum Role {
        SYSTEM, USER, ASSISTANT
    }
}
