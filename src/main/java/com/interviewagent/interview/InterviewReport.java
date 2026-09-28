package com.interviewagent.interview;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * 面试评估报告，由模型以结构化输出（JSON）生成。
 * <p>
 * Spring AI 会根据这个 record 生成 JSON Schema 发给模型，再把模型输出反序列化回来。
 * 字段上的 description 会进入 Schema，帮助模型理解每个字段该填什么。
 */
public record InterviewReport(
        @JsonPropertyDescription("综合得分，0 到 100")
        int overallScore,
        @JsonPropertyDescription("录用建议")
        Recommendation recommendation,
        @JsonPropertyDescription("两三句话的总体评价")
        String summary,
        @JsonPropertyDescription("分维度评分，例如基础知识、项目深度、系统设计、沟通表达")
        List<DimensionScore> dimensions,
        @JsonPropertyDescription("每道主问题的复盘")
        List<QuestionReview> questionReviews,
        @JsonPropertyDescription("具体、可执行的改进建议")
        List<String> suggestions) {

    public enum Recommendation {
        STRONG_HIRE, HIRE, LEAN_HIRE, NO_HIRE
    }

    public record DimensionScore(
            String dimension,
            @JsonPropertyDescription("1 到 5 分")
            int score,
            String comment) {
    }

    public record QuestionReview(
            String question,
            @JsonPropertyDescription("1 到 5 分")
            int score,
            @JsonPropertyDescription("对候选人回答的点评")
            String feedback,
            @JsonPropertyDescription("参考答案要点")
            String referenceAnswer) {
    }
}
