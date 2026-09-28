package com.interviewagent.questionbank;

import com.interviewagent.config.InterviewProperties;
import com.interviewagent.questionbank.Question.Difficulty;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * 按语义检索题目（RAG 的"检索"阶段）。
 * <p>
 * 查询文本会先被向量化，再在向量库里找最相近的题目，所以"高并发抢购"也能匹配到"秒杀系统"，
 * 不要求关键词完全一致。
 */
@Component
public class QuestionRetriever {

    private final VectorStore vectorStore;
    private final QuestionBank questionBank;
    private final int topK;

    public QuestionRetriever(VectorStore vectorStore, QuestionBank questionBank, InterviewProperties properties) {
        this.vectorStore = vectorStore;
        this.questionBank = questionBank;
        this.topK = properties.rag().topK();
    }

    /**
     * @param query      想考察的内容，自然语言
     * @param difficulty 难度，null 表示不限
     * @return 按相似度从高到低排列的题目
     */
    public List<Question> search(String query, Difficulty difficulty) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filter(difficulty))
                .build());
        return documents.stream()
                .map(doc -> questionBank.findById(doc.getId()))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Filter.Expression filter(Difficulty difficulty) {
        var b = new FilterExpressionBuilder();
        var source = b.eq("source", QuestionIndexer.SOURCE);
        return difficulty == null ? source.build() : b.and(source, b.eq("difficulty", difficulty.name())).build();
    }
}
