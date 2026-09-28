package com.interviewagent.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）：把多路检索的排序合并成一个。
 * <p>
 * 每个文档的得分 = Σ 1 / (k + 它在每一路结果中的名次)。
 * 只看名次、不看原始分数，所以不用操心向量相似度（0~1）和 BM25 分数（无上限）量纲不同的问题；
 * 在多路结果里都排得靠前的文档会胜出。k 通常取 60，用来削弱第一名相对后面名次的优势。
 */
public final class ReciprocalRankFusion {

    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {
    }

    /**
     * @param rankings 每一路检索的结果（文档 ID，按相关度从高到低）
     * @return 融合后的文档 ID，按得分从高到低；得分相同时保持先出现的在前
     */
    public static List<String> fuse(List<List<String>> rankings, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                scores.merge(ranking.get(rank), 1.0 / (k + rank + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }
}
