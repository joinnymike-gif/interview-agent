package com.interviewagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * 内存里的 BM25 关键词索引。
 * <p>
 * BM25 是搜索引擎最常用的关键词相关度算法，Elasticsearch 默认用的就是它。对查询里的每个词：
 * <ul>
 *   <li>词在文档里出现越多，分越高，但增长会饱和（参数 k1 控制）</li>
 *   <li>词在所有文档里越少见（IDF 越大），权重越高，所以 "hasQueuedPredecessors" 比 "原理" 更有区分度</li>
 *   <li>文档越长，分数按比例打折，避免长文档仅凭字多占便宜（参数 b 控制）</li>
 * </ul>
 * 适合几百到几万条的小语料。数据量再大就该交给 Elasticsearch，或者 PostgreSQL 全文检索加中文分词插件。
 */
public class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<String> ids = new ArrayList<>();
    private final List<Map<String, Integer>> termFrequencies = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();
    private final Map<String, Integer> documentFrequencies = new HashMap<>();
    private final double averageLength;

    /**
     * @param documents 文档 ID → 文本
     */
    public Bm25Index(Map<String, String> documents) {
        documents.forEach((id, text) -> {
            List<String> tokens = Tokenizer.tokenize(text);
            Map<String, Integer> tf = new HashMap<>();
            tokens.forEach(t -> tf.merge(t, 1, Integer::sum));
            tf.keySet().forEach(t -> documentFrequencies.merge(t, 1, Integer::sum));
            ids.add(id);
            termFrequencies.add(tf);
            lengths.add(tokens.size());
        });
        this.averageLength = lengths.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    /**
     * @param filter 按文档 ID 过滤，返回 false 的文档不参与排序
     * @return 得分大于 0 的文档 ID，按得分从高到低排列
     */
    public List<String> search(String query, int topK, Predicate<String> filter) {
        Set<String> queryTerms = new LinkedHashSet<>(Tokenizer.tokenize(query));
        double[] scores = new double[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            if (filter.test(ids.get(i))) {
                scores[i] = score(i, queryTerms);
            }
        }
        return IntStream.range(0, ids.size())
                .filter(i -> scores[i] > 0)
                .boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> scores[i]).reversed())
                .limit(topK)
                .map(ids::get)
                .toList();
    }

    private double score(int doc, Set<String> queryTerms) {
        Map<String, Integer> tf = termFrequencies.get(doc);
        double lengthNorm = 1 - B + B * lengths.get(doc) / averageLength;
        double score = 0;
        for (String term : queryTerms) {
            int f = tf.getOrDefault(term, 0);
            if (f > 0) {
                score += idf(term) * f * (K1 + 1) / (f + K1 * lengthNorm);
            }
        }
        return score;
    }

    private double idf(String term) {
        int n = ids.size();
        int df = documentFrequencies.getOrDefault(term, 0);
        return Math.log(1 + (n - df + 0.5) / (df + 0.5));
    }
}
