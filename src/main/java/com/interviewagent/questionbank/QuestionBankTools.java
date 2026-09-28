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
 */
@Component
public class QuestionBankTools {

    private static final int MAX_RESULTS = 5;

    private final QuestionBank questionBank;

    public QuestionBankTools(QuestionBank questionBank) {
        this.questionBank = questionBank;
    }

    @Tool(description = "列出题库中的所有题目分类")
    public List<String> listTopics() {
        return questionBank.topics();
    }

    @Tool(description = "按分类和难度从题库查找候选面试题，返回题目和考察要点。不确定有哪些分类时先调用 listTopics")
    public List<Question> searchQuestions(
            @ToolParam(description = "题目分类，例如 JVM、MySQL；不填表示不限分类", required = false) String topic,
            @ToolParam(description = "难度：EASY、MEDIUM 或 HARD；不填表示不限难度", required = false) Difficulty difficulty) {
        return questionBank.search(topic, difficulty, MAX_RESULTS);
    }
}
