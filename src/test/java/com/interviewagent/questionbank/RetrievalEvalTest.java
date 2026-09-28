package com.interviewagent.questionbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewagent.StubChatModel;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 检索效果评估：用真实的 embedding 模型，检查每条查询能不能找到预期的题目。
 * <p>
 * 查询故意写成和题目字面不同的说法（例如"高并发抢购活动"对应"秒杀系统"），考的是语义理解。
 * 换 embedding 模型、改索引文本格式、调 top-k 之后都跑一遍，用数字判断效果变好还是变差。
 * <p>
 * 需要调用 embedding 接口，设置了 EMBEDDING_API_KEY 才会运行：
 * <pre>
 * EMBEDDING_API_KEY=sk-xxx ./mvnw test -Dtest=RetrievalEvalTest
 * </pre>
 * 向量库用内存实现，不需要 PostgreSQL。
 */
@EnabledIfEnvironmentVariable(named = "EMBEDDING_API_KEY", matches = ".+")
@SpringBootTest(properties = {"spring.ai.model.chat=none", "spring.ai.model.embedding=openai"})
@ActiveProfiles("test")
class RetrievalEvalTest {

    /** recall@3 的及格线，低于它说明检索效果明显有问题 */
    private static final double MIN_RECALL_AT_3 = 0.8;

    record EvalCase(String query, String expected) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
            return SimpleVectorStore.builder(embeddingModel).build();
        }

        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    @Autowired
    QuestionRetriever retriever;

    @Test
    void retrievalRecall() throws Exception {
        List<EvalCase> cases;
        try (InputStream in = new ClassPathResource("eval/retrieval-cases.json").getInputStream()) {
            cases = JsonMapper.builder().build().readValue(in, new TypeReference<List<EvalCase>>() {
            });
        }

        int hitsAt1 = 0;
        int hitsAt3 = 0;
        System.out.printf("%n%-40s %-18s %s%n", "查询", "预期", "检索结果（按相似度排序）");
        for (EvalCase c : cases) {
            List<String> ids = retriever.search(c.query(), null).stream().map(Question::id).toList();
            int rank = ids.indexOf(c.expected());
            if (rank == 0) {
                hitsAt1++;
            }
            if (rank >= 0 && rank < 3) {
                hitsAt3++;
            }
            System.out.printf("%s %-40s %-18s %s%n", rank >= 0 && rank < 3 ? "✓" : "✗", c.query(), c.expected(), ids);
        }

        double recallAt1 = (double) hitsAt1 / cases.size();
        double recallAt3 = (double) hitsAt3 / cases.size();
        System.out.printf("%nrecall@1 = %.2f，recall@3 = %.2f（共 %d 条）%n", recallAt1, recallAt3, cases.size());

        assertThat(recallAt3).isGreaterThanOrEqualTo(MIN_RECALL_AT_3);
    }
}
