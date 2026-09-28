package com.interviewagent.questionbank;

import com.interviewagent.questionbank.Question.Difficulty;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 暴露给模型调用的题库工具（Function Calling）。
 * <p>
 * 模型根据 description 判断什么时候调用、传什么参数；Spring AI 负责执行方法并把结果回传给模型。
 * 所以 description 要写清楚用途和参数含义，它本质上也是提示词。
 * <p>
 * 检索做成工具、由模型自己决定查什么，而不是每轮对话前固定检索一次，
 * 这种方式常被称为 Agentic RAG：模型可以结合简历和候选人刚才的回答来组织查询。
 */
@Component
public class QuestionBankTools {

    private final QuestionRetriever retriever;

    public QuestionBankTools(QuestionRetriever retriever) {
        this.retriever = retriever;
    }

    @Tool(description = "从题库中按语义检索候选面试题，返回题目和考察要点，按相关度从高到低排列")
    public List<Question> searchQuestions(
            @ToolParam(description = "想考察的内容，用自然语言描述，可以结合候选人简历里的项目和技术栈，例如：订单系统的分布式事务、JVM 垃圾回收调优")
            String query,
            @ToolParam(description = "难度：EASY、MEDIUM 或 HARD；不填表示不限难度", required = false)
            Difficulty difficulty) {
        return retriever.search(query, difficulty);
    }
}
