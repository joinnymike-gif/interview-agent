package com.interviewagent.questionbank;

import com.interviewagent.config.InterviewProperties;
import com.interviewagent.questionbank.Question.Difficulty;
import com.interviewagent.rag.Bm25Index;
import com.interviewagent.rag.ReciprocalRankFusion;
import com.interviewagent.rag.Reranker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * 检索题目（RAG 的"检索"阶段），分三步：
 * <ol>
 *   <li><b>召回</b>：向量检索和关键词检索各取一批候选。向量检索懂语义，"高并发抢购"能找到"秒杀系统"；
 *       关键词检索对专有名词更准，"hasQueuedPredecessors" 这种词向量模型不一定认识</li>
 *   <li><b>融合</b>：用 RRF 把两路结果合并成一个排序</li>
 *   <li><b>重排序</b>：rerank 模型逐条比较查询和候选，选出最相关的 topK。接口出错时退回融合后的排序，
 *       rerank 只是锦上添花，不能因为它挂了就让面试中断</li>
 * </ol>
 * 各步骤拆成单独的方法，方便 RetrievalEvalTest 分别评估每一步的效果。
 */
@Component
public class QuestionRetriever {

    private static final Logger log = LoggerFactory.getLogger(QuestionRetriever.class);

    private final VectorStore vectorStore;
    private final QuestionBank questionBank;
    private final Reranker reranker;
    private final Bm25Index keywordIndex;
    private final int topK;
    private final int candidates;

    public QuestionRetriever(VectorStore vectorStore, QuestionBank questionBank, Reranker reranker,
                             InterviewProperties properties) {
        this.vectorStore = vectorStore;
        this.questionBank = questionBank;
        this.reranker = reranker;
        this.keywordIndex = new Bm25Index(questionBank.all().stream().collect(Collectors.toMap(
                Question::id, QuestionIndexer::indexText, (a, b) -> a, LinkedHashMap::new)));
        this.topK = properties.rag().topK();
        this.candidates = properties.rag().candidates();
    }

    /**
     * @param query      想考察的内容，可以是自然语言，也可以是技术名词
     * @param difficulty 难度，null 表示不限
     * @return 最相关的 topK 道题，按相关度从高到低
     */
    public List<Question> search(String query, Difficulty difficulty) {
        List<String> fused = hybridSearch(query, difficulty);
        List<String> ranked;
        try {
            ranked = rerank(query, fused);
        } catch (RuntimeException e) {
            log.warn("rerank 调用失败，改用混合检索的排序：{}", e.toString());
            ranked = fused.stream().limit(topK).toList();
        }
        return ranked.stream().map(questionBank::findById).flatMap(Optional::stream).toList();
    }

    /** 向量检索：按语义相似度召回 */
    List<String> vectorSearch(String query, Difficulty difficulty) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(candidates)
                .filterExpression(vectorFilter(difficulty))
                .build());
        return documents.stream()
                .map(Document::getId)
                .filter(id -> questionBank.findById(id).isPresent())
                .toList();
    }

    /** 关键词检索：按 BM25 召回 */
    List<String> keywordSearch(String query, Difficulty difficulty) {
        return keywordIndex.search(query, candidates, id -> questionBank.findById(id)
                .map(q -> difficulty == null || q.difficulty() == difficulty)
                .orElse(false));
    }

    /** 混合检索：两路召回用 RRF 融合，最多保留 candidates 条交给 rerank */
    List<String> hybridSearch(String query, Difficulty difficulty) {
        List<String> vector = vectorSearch(query, difficulty);
        List<String> keyword = keywordSearch(query, difficulty);
        List<String> fused = ReciprocalRankFusion.fuse(List.of(vector, keyword), ReciprocalRankFusion.DEFAULT_K)
                .stream().limit(candidates).toList();
        log.debug("检索「{}」向量召回 {}，关键词召回 {}，融合后 {}", query, vector, keyword, fused);
        return fused;
    }

    /** 用 rerank 模型对候选重新排序，取前 topK；接口出错时直接抛出 */
    List<String> rerank(String query, List<String> candidateIds) {
        List<String> texts = candidateIds.stream()
                .map(id -> QuestionIndexer.indexText(questionBank.findById(id).orElseThrow()))
                .toList();
        List<String> ranked = reranker.rerank(query, texts, topK).stream().map(candidateIds::get).toList();
        log.debug("检索「{}」rerank 后 {}", query, ranked);
        return ranked;
    }

    private static Filter.Expression vectorFilter(Difficulty difficulty) {
        var b = new FilterExpressionBuilder();
        var source = b.eq("source", QuestionIndexer.SOURCE);
        return difficulty == null ? source.build() : b.and(source, b.eq("difficulty", difficulty.name())).build();
    }
}
