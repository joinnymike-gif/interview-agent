package com.interviewagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

    @Test
    void documentRankedInBothListsWins() {
        // y 在两路结果里都靠前，胜过只在一路排第一的 x
        List<String> fused = ReciprocalRankFusion.fuse(List.of(List.of("x", "y", "z"), List.of("y", "w")), 60);

        assertThat(fused).containsExactly("y", "x", "w", "z");
    }

    @Test
    void tiesKeepFirstSeenOrder() {
        List<String> fused = ReciprocalRankFusion.fuse(List.of(List.of("a"), List.of("b")), 60);

        assertThat(fused).containsExactly("a", "b");
    }

    @Test
    void emptyRankingsFuseToEmpty() {
        assertThat(ReciprocalRankFusion.fuse(List.of(List.of(), List.of()), 60)).isEmpty();
    }
}
