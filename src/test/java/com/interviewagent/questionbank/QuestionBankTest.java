package com.interviewagent.questionbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewagent.questionbank.Question.Difficulty;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class QuestionBankTest {

    private QuestionBank questionBank;

    @BeforeEach
    void setUp() throws Exception {
        questionBank = new QuestionBank(JsonMapper.builder().build(), new ClassPathResource("question-bank.json"));
    }

    @Test
    void loadsTopics() {
        assertThat(questionBank.topics()).contains("JVM", "MySQL", "AI应用");
    }

    @Test
    void topicMatchIgnoresCaseAndWhitespace() {
        assertThat(questionBank.search("jvm", null, 5))
                .isNotEmpty()
                .allSatisfy(q -> assertThat(q.topic()).isEqualTo("JVM"));
        assertThat(questionBank.search("Java 基础", null, 5))
                .isNotEmpty()
                .allSatisfy(q -> assertThat(q.topic()).isEqualTo("Java基础"));
    }

    @Test
    void filtersByDifficultyAndRespectsLimit() {
        assertThat(questionBank.search(null, Difficulty.HARD, 3))
                .hasSize(3)
                .allSatisfy(q -> assertThat(q.difficulty()).isEqualTo(Difficulty.HARD));
    }

    @Test
    void unknownTopicReturnsEmpty() {
        assertThat(questionBank.search("不存在的分类", null, 5)).isEmpty();
    }

    @Test
    void exposesToolsWithSchemaForTheModel() {
        ToolCallback[] callbacks = ToolCallbacks.from(new QuestionBankTools(questionBank));

        ToolCallback search = Arrays.stream(callbacks)
                .filter(cb -> cb.getToolDefinition().name().equals("searchQuestions"))
                .findFirst()
                .orElseThrow();
        // 模型看到的参数 Schema 里应包含难度枚举，模型才知道能传哪些值
        assertThat(search.getToolDefinition().inputSchema()).contains("topic", "EASY", "MEDIUM", "HARD");

        String result = search.call("""
                {"topic": "Redis", "difficulty": "MEDIUM"}
                """);
        assertThat(result).contains("缓存穿透").doesNotContain("分布式锁");
    }
}
