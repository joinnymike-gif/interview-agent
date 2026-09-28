package com.interviewagent.config;

import java.time.Duration;
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
     * @param topK               最终返回给模型的题目数量
     * @param candidates         向量检索、关键词检索各召回多少条候选，融合后最多这么多条交给 rerank
     * @param embeddingBatchSize 每次调用 embedding 接口最多提交多少条文本（通义千问限制为 10）
     * @param rerank             重排序配置
     */
    public record Rag(int topK, int candidates, int embeddingBatchSize, Rerank rerank) {
    }

    /**
     * @param enabled 关闭后只做混合检索，不调用 rerank 接口
     * @param url     rerank 接口的完整地址
     * @param timeout 超时后放弃重排序，直接用混合检索的结果
     */
    public record Rerank(boolean enabled, String url, String apiKey, String model, Duration timeout) {
    }
}
