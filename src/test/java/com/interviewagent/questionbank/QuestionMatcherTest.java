package com.interviewagent.questionbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewagent.FakeEmbeddingModel;
import com.interviewagent.config.InterviewProperties;
import com.interviewagent.rag.Reranker;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class QuestionMatcherTest {

    private static final InterviewProperties PROPERTIES = new InterviewProperties(6, 30, 200,
            new InterviewProperties.Rag(3, 10, 10,
                    new InterviewProperties.Rerank(false, "", "", "", Duration.ofSeconds(5))));

    private QuestionBank questionBank;

    @BeforeEach
    void setUp() throws Exception {
        questionBank = new QuestionBank(JsonMapper.builder().build(), new ClassPathResource("question-bank.json"));
    }

    @Test
    void matchesAskedQuestionsToQuestionBankWithoutDuplicates() {
        var vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
        new QuestionIndexer(questionBank, vectorStore).reindex();
        var matcher = new QuestionMatcher(new QuestionRetriever(vectorStore, questionBank, Reranker.NONE, PROPERTIES));

        List<Question> matched = matcher.match(List.of(
                "请讲讲 Redis 分布式锁怎么实现？",
                "你提到了 Redlock，它有什么争议？",
                "HashMap 扩容和红黑树讲一下"));

        // 前两句都对应 redis-002，只保留一次
        assertThat(matched).extracting(Question::id).containsExactly("redis-002", "java-001");
        assertThat(matched.getFirst().keyPoints()).contains("锁续期（看门狗）");
    }

    @Test
    void returnsEmptyWhenRetrievalFails() {
        // embedding 接口不可用：检索会抛异常，评估应该照常进行，只是没有参考资料
        var vectorStore = SimpleVectorStore.builder(new FailingEmbeddingModel()).build();
        var matcher = new QuestionMatcher(new QuestionRetriever(vectorStore, questionBank, Reranker.NONE, PROPERTIES));

        assertThat(matcher.match(List.of("请讲讲 Redis 分布式锁怎么实现？"))).isEmpty();
    }

    @Test
    void noQuestionsNoReferences() {
        var vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
        var matcher = new QuestionMatcher(new QuestionRetriever(vectorStore, questionBank, Reranker.NONE, PROPERTIES));

        assertThat(matcher.match(List.of())).isEmpty();
    }

    private static class FailingEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new IllegalStateException("embedding 服务不可用");
        }

        @Override
        public float[] embed(Document document) {
            throw new IllegalStateException("embedding 服务不可用");
        }
    }
}
