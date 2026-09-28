package com.interviewagent.config;

import java.util.List;
import java.util.stream.IntStream;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;

/**
 * 向量化时的分批策略：先按 Spring AI 默认的 token 数分批，再限制每批的条数。
 * <p>
 * 很多 embedding 接口对单次请求的条数有上限（例如通义千问 text-embedding-v4 最多 10 条），
 * 默认策略只看 token 数，文档一多就会超限报错。
 */
public class MaxSizeBatchingStrategy implements BatchingStrategy {

    private final BatchingStrategy tokenCountStrategy = new TokenCountBatchingStrategy();
    private final int maxBatchSize;

    public MaxSizeBatchingStrategy(int maxBatchSize) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be positive: " + maxBatchSize);
        }
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        return tokenCountStrategy.batch(documents).stream()
                .flatMap(batch -> IntStream.range(0, (batch.size() + maxBatchSize - 1) / maxBatchSize)
                        .mapToObj(i -> batch.subList(i * maxBatchSize, Math.min(batch.size(), (i + 1) * maxBatchSize))))
                .toList();
    }
}
