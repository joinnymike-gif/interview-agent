package com.interviewagent.questionbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewagent.StubChatModel;
import com.interviewagent.rag.Reranker;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
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
 * 检索效果评估：用真实的 embedding 和 rerank 模型，对比每种检索策略能不能找到预期的题目。
 * <p>
 * 用例分两类（见 eval/retrieval-cases.json）：
 * <ul>
 *   <li>semantic：和题目字面不同的说法，例如"高并发抢购活动"对应"秒杀系统"，考语义理解，向量检索擅长</li>
 *   <li>keyword：精确的技术名词，例如 "hasQueuedPredecessors"，关键词检索擅长</li>
 * </ul>
 * 换模型、改索引文本、调 top-k 或候选数之后都跑一遍，用数字判断效果变好还是变差。
 * <p>
 * 需要调用 embedding 接口，设置了 EMBEDDING_API_KEY 才会运行；rerank 默认复用这个 Key，
 * 不想测 rerank 可以设置 RERANK_ENABLED=false：
 * <pre>
 * EMBEDDING_API_KEY=sk-xxx ./mvnw test -Dtest=RetrievalEvalTest
 * </pre>
 * 向量库用内存实现，不需要 PostgreSQL。
 */
@EnabledIfEnvironmentVariable(named = "EMBEDDING_API_KEY", matches = ".+")
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=openai",
        "interview.rag.rerank.enabled=${RERANK_ENABLED:true}"})
@ActiveProfiles("test")
class RetrievalEvalTest {

    /** 最终检索流程 recall@3 的及格线，低于它说明检索效果明显有问题 */
    private static final double MIN_RECALL_AT_3 = 0.8;

    record EvalCase(String type, String query, String expected) {
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

    @Autowired
    Reranker reranker;

    @Test
    void compareRetrievalStrategies() throws Exception {
        List<EvalCase> cases;
        try (InputStream in = new ClassPathResource("eval/retrieval-cases.json").getInputStream()) {
            cases = JsonMapper.builder().build().readValue(in, new TypeReference<List<EvalCase>>() {
            });
        }

        Map<String, BiFunction<String, List<String>, List<String>>> strategies = new LinkedHashMap<>();
        strategies.put("向量检索", (query, hybrid) -> retriever.vectorSearch(query, null));
        strategies.put("关键词检索", (query, hybrid) -> retriever.keywordSearch(query, null));
        strategies.put("混合检索", (query, hybrid) -> hybrid);
        boolean rerankEnabled = reranker != Reranker.NONE;
        if (rerankEnabled) {
            strategies.put("混合 + rerank", retriever::rerank);
        }

        // 每条用例在每种策略下的名次（从 1 开始，没找到为 0）
        Map<String, List<Integer>> ranks = new LinkedHashMap<>();
        strategies.keySet().forEach(name -> ranks.put(name, new ArrayList<>()));
        System.out.printf("%n%-8s %-36s %-18s %s%n", "类型", "查询", "预期", "各策略下的名次（- 表示没找到）");
        for (EvalCase c : cases) {
            List<String> hybrid = retriever.hybridSearch(c.query(), null);
            List<String> row = new ArrayList<>();
            strategies.forEach((name, strategy) -> {
                int rank = strategy.apply(c.query(), hybrid).indexOf(c.expected()) + 1;
                ranks.get(name).add(rank);
                row.add(name + " " + (rank == 0 ? "-" : rank));
            });
            System.out.printf("%-8s %-36s %-18s %s%n", c.type(), c.query(), c.expected(), String.join("  ", row));
        }

        System.out.printf("%n%-14s %10s %10s %14s %14s%n", "策略", "recall@1", "recall@3", "semantic@3", "keyword@3");
        ranks.forEach((name, r) -> System.out.printf("%-14s %10.2f %10.2f %14.2f %14.2f%n", name,
                recall(cases, r, 1, null), recall(cases, r, 3, null),
                recall(cases, r, 3, "semantic"), recall(cases, r, 3, "keyword")));

        String finalStrategy = rerankEnabled ? "混合 + rerank" : "混合检索";
        assertThat(recall(cases, ranks.get(finalStrategy), 3, null))
                .as("%s 的 recall@3", finalStrategy)
                .isGreaterThanOrEqualTo(MIN_RECALL_AT_3);
    }

    /** 预期题目排在前 k 名的用例占比；type 为 null 时统计全部用例 */
    private static double recall(List<EvalCase> cases, List<Integer> ranks, int k, String type) {
        int total = 0;
        int hits = 0;
        for (int i = 0; i < cases.size(); i++) {
            if (type == null || type.equals(cases.get(i).type())) {
                total++;
                int rank = ranks.get(i);
                if (rank >= 1 && rank <= k) {
                    hits++;
                }
            }
        }
        return total == 0 ? 0 : (double) hits / total;
    }
}
