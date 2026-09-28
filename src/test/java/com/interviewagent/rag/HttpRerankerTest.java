package com.interviewagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 用本地 HTTP 服务模拟 rerank 接口，检查真实的请求和响应处理（包括超时）。
 */
class HttpRerankerTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> contentLength = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody;
    private volatile long delayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsStandardRequestAndReturnsIndicesByScore() {
        responseBody = """
                {"object": "list", "model": "qwen3-rerank", "id": "abc", "usage": {"total_tokens": 30},
                 "results": [{"index": 2, "relevance_score": 0.35}, {"index": 0, "relevance_score": 0.91}]}
                """;

        List<Integer> ranked = reranker(Duration.ofSeconds(5)).rerank("分布式锁", List.of("锁", "缓存", "队列"), 2);

        assertThat(ranked).containsExactly(0, 2);
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        // 带 Content-Length 发送，而不是分块传输
        assertThat(contentLength.get()).isNotNull();
        assertThat(Integer.parseInt(contentLength.get())).isEqualTo(requestBody.get().getBytes(StandardCharsets.UTF_8).length);
        assertThat(requestBody.get())
                .contains("\"model\":\"qwen3-rerank\"", "\"query\":\"分布式锁\"", "\"documents\":[\"锁\",\"缓存\",\"队列\"]",
                        "\"top_n\":2");
    }

    @Test
    void rejectsOutOfRangeIndex() {
        responseBody = """
                {"results": [{"index": 5, "relevance_score": 0.9}]}
                """;

        assertThatThrownBy(() -> reranker(Duration.ofSeconds(5)).rerank("q", List.of("a", "b"), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("越界");
    }

    @Test
    void surfacesHttpErrors() {
        status = 401;
        responseBody = """
                {"error": {"message": "Incorrect API key provided."}}
                """;

        assertThatThrownBy(() -> reranker(Duration.ofSeconds(5)).rerank("q", List.of("a"), 1))
                .isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void timesOut() {
        delayMillis = 1_000;
        responseBody = """
                {"results": []}
                """;

        assertThatThrownBy(() -> reranker(Duration.ofMillis(200)).rerank("q", List.of("a"), 1))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void skipsCallWhenNoDocuments() {
        assertThat(reranker(Duration.ofSeconds(5)).rerank("q", List.of(), 3)).isEmpty();
        assertThat(requestBody.get()).isNull();
    }

    private HttpReranker reranker(Duration timeout) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/rerank";
        return new HttpReranker(RestClient.builder(), url, "test-key", "qwen3-rerank", timeout);
    }
}
