package com.interviewagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenizerTest {

    @Test
    void splitsChineseIntoBigramsAndLowercasesWords() {
        assertThat(Tokenizer.tokenize("Redis 分布式锁")).containsExactly("redis", "分布", "布式", "式锁");
    }

    @Test
    void keepsLettersAndDigitsTogether() {
        assertThat(Tokenizer.tokenize("G1 收集器 JDK8")).containsExactly("g1", "收集", "集器", "jdk8");
    }

    @Test
    void singleChineseCharacterIsKeptAsIs() {
        assertThat(Tokenizer.tokenize("锁")).containsExactly("锁");
    }

    @Test
    void splitsOnPunctuationAndScriptChanges() {
        assertThat(Tokenizer.tokenize("Full-GC，MVCC和ReadView")).containsExactly("full", "gc", "mvcc", "和", "readview");
    }

    @Test
    void emptyTextHasNoTokens() {
        assertThat(Tokenizer.tokenize(" ，。")).isEmpty();
    }
}
