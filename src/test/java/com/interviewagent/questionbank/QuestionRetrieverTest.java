package com.interviewagent.questionbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewagent.FakeEmbeddingModel;
import com.interviewagent.config.InterviewProperties;
import com.interviewagent.questionbank.Question.Difficulty;
import com.interviewagent.rag.Reranker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用假向量模型 + 内存向量库 + 假 rerank 测试"索引 → 召回 → 融合 → 重排序"链路。
 * 检索质量（真实模型下能不能找对题）见 RetrievalEvalTest。
 */
class QuestionRetrieverTest {

    private QuestionBank questionBank;
    private SimpleVectorStore vectorStore;
    private QuestionIndexer indexer;
    private QuestionRetriever retriever;

    @BeforeEach
    void setUp() throws Exception {
        questionBank = new QuestionBank(JsonMapper.builder().build(), new ClassPathResource("question-bank.json"));
        vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
        indexer = new QuestionIndexer(questionBank, vectorStore);
        retriever = retrieverWith(Reranker.NONE);
        indexer.reindex();
    }

    private QuestionRetriever retrieverWith(Reranker reranker) {
        var rerank = new InterviewProperties.Rerank(false, "", "", "", Duration.ofSeconds(5));
        return new QuestionRetriever(vectorStore, questionBank, reranker,
                new InterviewProperties(6, 30, 200, new InterviewProperties.Rag(3, 10, 10, rerank)));
    }

    @Test
    void indexesEveryQuestionWithMetadata() {
        List<Document> all = allDocuments();
        assertThat(all).hasSameSizeAs(questionBank.all());

        Document redisLock = all.stream().filter(d -> d.getId().equals("redis-002")).findFirst().orElseThrow();
        assertThat(redisLock.getText()).contains("分布式锁", "看门狗");
        assertThat(redisLock.getMetadata())
                .containsEntry("source", QuestionIndexer.SOURCE)
                .containsEntry("topic", "Redis")
                .containsEntry("difficulty", "HARD");
    }

    @Test
    void reindexDoesNotDuplicate() {
        indexer.reindex();
        assertThat(allDocuments()).hasSameSizeAs(questionBank.all());
    }

    @Test
    void returnsMostSimilarQuestionFirst() {
        List<Question> results = retriever.search("Redis 分布式锁怎么实现", null);

        assertThat(results).hasSize(3);
        assertThat(results.getFirst().id()).isEqualTo("redis-002");
    }

    @Test
    void filtersByDifficulty() {
        assertThat(retriever.search("Redis 分布式锁怎么实现", Difficulty.MEDIUM))
                .isNotEmpty()
                .allSatisfy(q -> assertThat(q.difficulty()).isEqualTo(Difficulty.MEDIUM))
                .extracting(Question::id)
                .doesNotContain("redis-002");
    }

    @Test
    void ignoresDocumentsFromOtherSources() {
        vectorStore.add(List.of(Document.builder()
                .id("note-1")
                .text("Redis 分布式锁怎么实现")
                .metadata("source", "notes")
                .build()));

        assertThat(retriever.search("Redis 分布式锁怎么实现", null))
                .extracting(Question::id)
                .doesNotContain("note-1")
                .first().isEqualTo("redis-002");
    }

    @Test
    void keywordSearchFindsExactTechnicalTerms() {
        // 这类专有名词只在一道题里出现，关键词检索能精确命中
        assertThat(retriever.keywordSearch("hasQueuedPredecessors", null)).containsExactly("concurrency-002");
        assertThat(retriever.keywordSearch("CounterCell", null)).containsExactly("java-002");
    }

    @Test
    void hybridSearchFusesBothRetrievers() {
        String query = "MaxGCPauseMillis";
        List<String> vector = retriever.vectorSearch(query, null);
        List<String> keyword = retriever.keywordSearch(query, null);

        List<String> hybrid = retriever.hybridSearch(query, null);

        // 融合结果来自两路召回的并集，最多保留 candidates（10）条
        assertThat(hybrid).hasSizeLessThanOrEqualTo(10)
                .doesNotHaveDuplicates()
                .isSubsetOf(Stream.concat(vector.stream(), keyword.stream()).toList());
        // 只有 jvm-002 的考察要点里有 MaxGCPauseMillis，两路结果融合后它应该排第一
        assertThat(keyword).first().isEqualTo("jvm-002");
        assertThat(hybrid).first().isEqualTo("jvm-002");
    }

    @Test
    void keywordSearchAppliesDifficultyFilter() {
        assertThat(retriever.keywordSearch("Redis", Difficulty.MEDIUM)).containsExactly("redis-001");
    }

    @Test
    void rerankerDecidesFinalOrder() {
        List<String> receivedDocuments = new ArrayList<>();
        // 假 rerank：把候选的顺序整个倒过来
        Reranker reversing = (query, documents, topN) -> {
            receivedDocuments.addAll(documents);
            return IntStream.iterate(documents.size() - 1, i -> i >= 0, i -> i - 1).limit(topN).boxed().toList();
        };
        String query = "Redis 分布式锁怎么实现";
        List<String> hybrid = retriever.hybridSearch(query, null);

        List<Question> results = retrieverWith(reversing).search(query, null);

        assertThat(results).extracting(Question::id)
                .containsExactlyElementsOf(hybrid.reversed().subList(0, 3));
        // rerank 收到的是完整的索引文本，不只是题目
        assertThat(receivedDocuments).anySatisfy(d -> assertThat(d).contains("分类：Redis", "考察要点："));
    }

    @Test
    void fallsBackToHybridOrderWhenRerankFails() {
        Reranker failing = (query, documents, topN) -> {
            throw new IllegalStateException("rerank 服务不可用");
        };

        List<Question> results = retrieverWith(failing).search("Redis 分布式锁怎么实现", null);

        assertThat(results).extracting(Question::id)
                .containsExactlyElementsOf(retriever.hybridSearch("Redis 分布式锁怎么实现", null).subList(0, 3));
    }

    @Test
    void exposesSearchToolWithSchemaForTheModel() {
        ToolCallback[] callbacks = ToolCallbacks.from(new QuestionBankTools(retriever));

        assertThat(callbacks).hasSize(1);
        ToolCallback search = callbacks[0];
        assertThat(search.getToolDefinition().name()).isEqualTo("searchQuestions");
        // 模型看到的参数 Schema 里应包含难度枚举，模型才知道能传哪些值
        assertThat(search.getToolDefinition().inputSchema()).contains("query", "EASY", "MEDIUM", "HARD");

        String result = search.call("""
                {"query": "Redis 分布式锁怎么实现"}
                """);
        assertThat(result).contains("redis-002", "看门狗");
    }

    private List<Document> allDocuments() {
        return vectorStore.similaritySearch(SearchRequest.builder().query("题目").topK(100).build());
    }
}
