package com.interviewagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 面试相关配置，对应 application.yml 中的 interview.*。
 *
 * @param maxQuestions      主问题数量（追问不计），写进系统提示词，由模型把控节奏
 * @param maxAnswers        单场面试最多回答次数，超过后必须结束面试，用于兜底控制成本
 * @param memoryMaxMessages 对话记忆保留的最近消息条数
 * @param rag               检索相关配置
 */
@ConfigurationProperties(prefix = "interview")
public record InterviewProperties(int maxQuestions, int maxAnswers, int memoryMaxMessages, Rag rag) {

    /**
     * @param topK               每次检索返回的题目数量
     * @param embeddingBatchSize 每次调用 embedding 接口最多提交多少条文本（通义千问限制为 10）
     */
    public record Rag(int topK, int embeddingBatchSize) {
    }
}
