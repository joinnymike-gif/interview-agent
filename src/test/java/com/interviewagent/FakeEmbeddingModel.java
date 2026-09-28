package com.interviewagent;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 测试用的假向量模型：按字符出现次数生成向量（字符袋），不发网络请求。
 * <p>
 * 效果远不如真实模型（只认字面重叠，不懂语义），但结果确定，
 * 足够测试"写入向量库 → 按相似度检索 → 条件过滤"这条链路。
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 512;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(embed(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        text.toLowerCase().codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(cp -> vector[Math.floorMod(cp * 31, DIMENSIONS)] += 1);
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
