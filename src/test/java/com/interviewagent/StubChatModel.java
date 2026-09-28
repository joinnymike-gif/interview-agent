package com.interviewagent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * 测试用的假模型：不发网络请求，返回固定回复，并记录收到的每个 Prompt 供断言。
 */
public class StubChatModel implements ChatModel {

    public static final String REPORT_JSON = """
            {
              "overallScore": 72,
              "recommendation": "HIRE",
              "summary": "基础扎实，系统设计经验偏少。",
              "dimensions": [{"dimension": "基础知识", "score": 4, "comment": "HashMap 讲得清楚"}],
              "questionReviews": [{"question": "HashMap 原理", "score": 4, "feedback": "要点基本完整",
                                   "missedPoints": ["扩容时高低位拆分"], "referenceAnswer": "数组 + 链表 + 红黑树"}],
              "suggestions": ["补充 G1 的 Region 和 Mixed GC 流程"]
            }
            """;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (isEvaluatorPrompt(prompt)) {
            return response(REPORT_JSON);
        }
        // 按本场面试历史里已有的面试官消息数编号，不同面试之间互不影响
        long previousReplies = prompt.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.ASSISTANT)
                .count();
        return response("面试官回复 " + (previousReplies + 1));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        return Flux.just("流式", "回复").map(StubChatModel::response);
    }

    @Override
    public ChatOptions getOptions() {
        // 返回 ToolCallingChatOptions，ChatClient 才会把工具定义放进请求
        return ToolCallingChatOptions.builder().build();
    }

    public Prompt lastPrompt() {
        return prompts.getLast();
    }

    private static boolean isEvaluatorPrompt(Prompt prompt) {
        return prompt.getSystemMessage().getText().contains("评估专家");
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
