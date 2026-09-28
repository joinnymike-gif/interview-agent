package com.interviewagent.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 调用 rerank 接口。
 * <p>
 * rerank 没有像 OpenAI Chat 那样的统一标准，这里实现的是用得最广的一种格式（Cohere、Jina 最早采用）：
 * <pre>
 * 请求：{"model": "...", "query": "...", "documents": ["...", "..."], "top_n": 5}
 * 响应：{"results": [{"index": 2, "relevance_score": 0.93}, ...]}
 * </pre>
 * 百炼的 qwen3-rerank（compatible-api/v1/reranks）、硅基流动、Jina、Cohere、本地部署的 vLLM 都支持这种格式。
 */
public class HttpReranker implements Reranker {

    private final RestClient restClient;
    private final String model;

    public HttpReranker(RestClient.Builder builder, String url, String apiKey, String model, Duration timeout) {
        var jdkRequestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        jdkRequestFactory.setReadTimeout(timeout);
        this.restClient = builder
                .baseUrl(url)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                // 先把请求体缓冲下来，带上 Content-Length 发送；否则会用分块传输（chunked），有些网关不支持
                .requestFactory(new BufferingClientHttpRequestFactory(jdkRequestFactory))
                .build();
        this.model = model;
    }

    record RerankRequest(String model, String query, List<String> documents, @JsonProperty("top_n") int topN) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResponse(List<Result> results) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(int index, @JsonProperty("relevance_score") double relevanceScore) {
        }
    }

    @Override
    public List<Integer> rerank(String query, List<String> documents, int topN) {
        if (documents.isEmpty()) {
            return List.of();
        }
        RerankResponse response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RerankRequest(model, query, documents, topN))
                .retrieve()
                .body(RerankResponse.class);
        if (response == null || response.results() == null) {
            throw new IllegalStateException("rerank 接口没有返回 results 字段");
        }
        for (RerankResponse.Result result : response.results()) {
            if (result.index() < 0 || result.index() >= documents.size()) {
                throw new IllegalStateException("rerank 接口返回了越界的下标：" + result.index());
            }
        }
        // 接口一般已经按分数排好序，这里再排一次，不依赖具体厂商的实现
        return response.results().stream()
                .sorted(Comparator.comparingDouble(RerankResponse.Result::relevanceScore).reversed())
                .limit(topN)
                .map(RerankResponse.Result::index)
                .toList();
    }
}
