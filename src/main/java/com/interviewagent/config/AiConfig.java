package com.interviewagent.config;

import com.interviewagent.questionbank.QuestionBankTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * 大模型相关的 Bean。
 * <p>
 * 这里只依赖 Spring AI 的通用抽象（ChatClient / ChatMemory），不依赖具体厂商，
 * 所以切换 Anthropic、DeepSeek、通义千问等模型只需要改配置，不用改代码。
 */
@Configuration
public class AiConfig {

    /**
     * 对话记忆：按会话 ID 保存最近的消息。
     * 默认存在内存里（InMemoryChatMemoryRepository），重启即丢失；
     * 上生产时换成 JDBC / Redis 实现的 ChatMemoryRepository 即可。
     */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, InterviewProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(properties.memoryMaxMessages())
                .build();
    }

    /**
     * 面试官：带对话记忆和题库工具，负责出题和追问。
     */
    @Bean
    ChatClient interviewerChatClient(ChatClient.Builder builder,
                                     ChatMemory chatMemory,
                                     QuestionBankTools questionBankTools,
                                     @Value("classpath:prompts/interviewer-system.st") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .defaultTools(questionBankTools)
                .build();
    }

    /**
     * 评估官：不带记忆和工具，只根据面试记录输出结构化评估报告。
     * 和面试官分开，是为了让评估不受面试过程中角色设定的影响。
     */
    @Bean
    ChatClient evaluatorChatClient(ChatClient.Builder builder,
                                   @Value("classpath:prompts/evaluator-system.st") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}
