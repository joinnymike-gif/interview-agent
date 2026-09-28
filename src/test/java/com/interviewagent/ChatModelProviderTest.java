package com.interviewagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 验证 spring.ai.model.chat 能切换模型厂商，且只配一家的 Key 就能正常启动。
 * 这里只检查装配，不会真的调用模型。
 */
class ChatModelProviderTest {

    @Nested
    @SpringBootTest(properties = {"spring.ai.model.chat=anthropic", "spring.ai.anthropic.api-key=test-key"})
    class Anthropic {

        @Autowired
        ChatModel chatModel;

        @Test
        void usesAnthropicChatModel() {
            assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
            assertThat(chatModel.getOptions().getModel()).isEqualTo("claude-opus-5");
        }
    }

    @Nested
    @SpringBootTest(properties = "spring.ai.openai.chat.api-key=test-key")
    class DeepSeekByDefault {

        @Autowired
        ChatModel chatModel;

        @Test
        void usesDeepSeekThroughOpenAiCompatibleApi() {
            assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
            assertThat(chatModel.getOptions().getModel()).isEqualTo("deepseek-chat");
        }
    }
}
