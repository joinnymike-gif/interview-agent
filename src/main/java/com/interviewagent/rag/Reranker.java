package com.interviewagent.rag;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 重排序（rerank）：对召回的候选文档逐个和查询比较，重新打分排序。
 * <p>
 * 向量检索是把查询和文档各自转成向量再比较，速度快但比较粗；rerank 模型把查询和文档放在一起读，
 * 判断更准，但慢且按次收费。所以通常先用便宜的方法召回几十条候选，再用 rerank 精排出前几条。
 */
public interface Reranker {

    /** 不做重排序，保持原顺序 */
    Reranker NONE = (query, documents, topN) -> IntStream.range(0, Math.min(topN, documents.size())).boxed().toList();

    /**
     * @return 最相关的 topN 个文档在 documents 中的下标，按相关度从高到低
     */
    List<Integer> rerank(String query, List<String> documents, int topN);
}
