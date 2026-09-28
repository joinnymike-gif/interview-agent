package com.interviewagent.questionbank;

import com.interviewagent.questionbank.Question.Difficulty;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 题库：启动时从 classpath 的 JSON 文件加载。
 * <p>
 * 目前按分类和难度做精确过滤。后续做 RAG 时，可以把题目向量化存进向量库，
 * 改成按候选人简历和回答内容做语义检索。
 */
@Component
public class QuestionBank {

    private final List<Question> questions;

    public QuestionBank(JsonMapper jsonMapper,
                        @Value("classpath:question-bank.json") Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            this.questions = List.copyOf(jsonMapper.readValue(in, new TypeReference<List<Question>>() {
            }));
        }
    }

    public List<String> topics() {
        return questions.stream().map(Question::topic).distinct().toList();
    }

    /**
     * 按分类和难度查找题目，参数为空表示不限。分类匹配忽略大小写和空格，
     * 因为模型传过来的可能是 "jvm"、"Java 基础" 这样的写法。
     */
    public List<Question> search(String topic, Difficulty difficulty, int limit) {
        String normalizedTopic = normalize(topic);
        return questions.stream()
                .filter(q -> normalizedTopic.isEmpty() || normalize(q.topic()).contains(normalizedTopic))
                .filter(q -> difficulty == null || q.difficulty() == difficulty)
                .limit(limit)
                .toList();
    }

    private static String normalize(String text) {
        return StringUtils.hasText(text) ? StringUtils.trimAllWhitespace(text).toLowerCase(Locale.ROOT) : "";
    }
}
