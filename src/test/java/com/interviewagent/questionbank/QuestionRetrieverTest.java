package com.interviewagent.questionbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewagent.FakeEmbeddingModel;
import com.interviewagent.config.InterviewProperties;
import com.interviewagent.questionbank.Question.Difficulty;
import java.util.List;
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
 * 用假向量模型 + 内存向量库测试"索引 → 检索 → 过滤"链路。
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
        retriever = new QuestionRetriever(vectorStore, questionBank,
                new InterviewProperties(6, 30, 200, new InterviewProperties.Rag(3, 10)));
        indexer.reindex();
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
