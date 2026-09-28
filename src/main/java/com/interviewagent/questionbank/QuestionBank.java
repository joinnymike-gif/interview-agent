package com.interviewagent.questionbank;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 题库：启动时从 classpath 的 JSON 文件加载，是题目内容的唯一来源。
 * 向量库里只存题目的向量索引，检索到 ID 后回到这里取完整题目。
 */
@Component
public class QuestionBank {

    private final List<Question> questions;
    private final Map<String, Question> questionsById;

    public QuestionBank(JsonMapper jsonMapper,
                        @Value("classpath:question-bank.json") Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            this.questions = List.copyOf(jsonMapper.readValue(in, new TypeReference<List<Question>>() {
            }));
        }
        this.questionsById = questions.stream().collect(Collectors.toUnmodifiableMap(Question::id, Function.identity()));
    }

    public List<Question> all() {
        return questions;
    }

    public Optional<Question> findById(String id) {
        return Optional.ofNullable(questionsById.get(id));
    }
}
