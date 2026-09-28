package com.interviewagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Bm25IndexTest {

    private final Bm25Index index = new Bm25Index(new LinkedHashMap<>(Map.of(
            "hashmap", "HashMap 的底层原理，扩容与红黑树",
            "aqs", "AQS 的底层原理，公平锁调用 hasQueuedPredecessors",
            "redis-lock", "Redis 分布式锁的原理与看门狗续期",
            "mvcc", "MVCC 的原理与 ReadView")));

    @Test
    void rareTermOutweighsCommonTerm() {
        // "原理" 每篇都有，几乎不加分；"hasQueuedPredecessors" 只出现在一篇里，决定了排序
        assertThat(index.search("hasQueuedPredecessors 的原理", 10, id -> true)).first().isEqualTo("aqs");
    }

    @Test
    void ranksByMatchedTerms() {
        assertThat(index.search("Redis 分布式锁", 10, id -> true)).first().isEqualTo("redis-lock");
        assertThat(index.search("readview", 10, id -> true)).containsExactly("mvcc");
    }

    @Test
    void appliesFilterAndLimit() {
        assertThat(index.search("底层原理", 10, id -> !id.equals("aqs"))).doesNotContain("aqs");
        assertThat(index.search("原理", 2, id -> true)).hasSize(2);
    }

    @Test
    void returnsNothingWhenNoTermMatches() {
        assertThat(index.search("Kafka 消息积压", 10, id -> true)).isEmpty();
    }
}
