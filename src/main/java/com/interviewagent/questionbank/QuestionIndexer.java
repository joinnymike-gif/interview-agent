package com.interviewagent.questionbank;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 把题库写入向量库（RAG 的"索引"阶段）。
 * <p>
 * 每次启动全量重建：先删掉旧的题库数据，再把所有题目向量化后写入。
 * 这样题库 JSON 里删改的题目也能同步过去。题目多了以后每次启动都重新向量化会比较慢，
 * 可以改成按内容哈希做增量更新。
 */
@Component
public class QuestionIndexer implements ApplicationRunner {

    /** 向量库里题库数据的来源标记，和以后可能加入的其他资料（如知识库文档）区分开 */
    static final String SOURCE = "question-bank";

    private static final Logger log = LoggerFactory.getLogger(QuestionIndexer.class);

    private final QuestionBank questionBank;
    private final VectorStore vectorStore;

    public QuestionIndexer(QuestionBank questionBank, VectorStore vectorStore) {
        this.questionBank = questionBank;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        reindex();
    }

    public void reindex() {
        vectorStore.delete(new FilterExpressionBuilder().eq("source", SOURCE).build());
        List<Document> documents = questionBank.all().stream().map(QuestionIndexer::toDocument).toList();
        vectorStore.add(documents);
        log.info("题库索引完成，共 {} 道题", documents.size());
    }

    /**
     * 一道题对应一个 Document。参与向量化的文本包含分类、题目和考察要点：
     * 候选人简历里常出现的是具体技术点（如 "Region"、"分布式锁"），把要点也放进去更容易检索到。
     * 分类和难度同时放进 metadata，用于检索时按条件过滤。
     */
    static Document toDocument(Question question) {
        String text = """
                分类：%s
                题目：%s
                考察要点：%s""".formatted(question.topic(), question.question(), String.join("；", question.keyPoints()));
        return Document.builder()
                .id(question.id())
                .text(text)
                .metadata(Map.of(
                        "source", SOURCE,
                        "topic", question.topic(),
                        "difficulty", question.difficulty().name()))
                .build();
    }
}
