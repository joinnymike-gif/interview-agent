package com.interviewagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class MaxSizeBatchingStrategyTest {

    @Test
    void splitsIntoBatchesOfAtMostMaxSize() {
        List<Document> documents = IntStream.range(0, 25)
                .mapToObj(i -> new Document("短文本 " + i))
                .toList();

        List<List<Document>> batches = new MaxSizeBatchingStrategy(10).batch(documents);

        assertThat(batches).extracting(List::size).containsExactly(10, 10, 5);
        assertThat(batches.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(documents);
    }

    @Test
    void rejectsNonPositiveSize() {
        assertThatThrownBy(() -> new MaxSizeBatchingStrategy(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
