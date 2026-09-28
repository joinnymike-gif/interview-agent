package com.interviewagent.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.interviewagent.interview.InterviewReport.Recommendation;
import java.util.List;
import org.junit.jupiter.api.Test;

class GradingMetricsTest {

    @Test
    void meanAbsoluteError() {
        assertThat(GradingMetrics.meanAbsoluteError(List.of(80.0, 60.0), List.of(70.0, 65.0))).isEqualTo(7.5);
    }

    @Test
    void recommendationDistance() {
        assertThat(GradingMetrics.distance(Recommendation.STRONG_HIRE, Recommendation.LEAN_HIRE)).isEqualTo(2);
        assertThat(GradingMetrics.distance(Recommendation.NO_HIRE, Recommendation.NO_HIRE)).isZero();
    }

    @Test
    void spearmanOnlyCaresAboutOrder() {
        // 分数整体偏高 20 分，但排序完全一致
        assertThat(GradingMetrics.spearman(List.of(90.0, 70.0, 50.0), List.of(70.0, 50.0, 30.0))).isEqualTo(1.0);
        assertThat(GradingMetrics.spearman(List.of(1.0, 2.0, 3.0), List.of(3.0, 2.0, 1.0))).isEqualTo(-1.0);
    }

    @Test
    void spearmanHandlesTies() {
        assertThat(GradingMetrics.ranks(List.of(1.0, 2.0, 2.0, 3.0))).containsExactly(1.0, 2.5, 2.5, 4.0);
        assertThat(GradingMetrics.spearman(List.of(1.0, 2.0, 2.0, 3.0), List.of(1.0, 2.0, 3.0, 4.0)))
                .isCloseTo(0.9487, within(0.0001));
    }

    @Test
    void spearmanIsUndefinedWithoutVariance() {
        assertThat(GradingMetrics.spearman(List.of(65.0, 65.0), List.of(80.0, 30.0))).isNaN();
    }

    @Test
    void mentionsMatchesAnyAlternativeIgnoringCase() {
        List<String> missed = List.of("没有提到用 EXPLAIN 看执行计划", "未设置锁的超时时间");

        assertThat(GradingMetrics.mentions(missed, "explain|执行计划")).isTrue();
        assertThat(GradingMetrics.mentions(missed, "过期|超时")).isTrue();
        assertThat(GradingMetrics.mentions(missed, "幂等")).isFalse();
        assertThat(GradingMetrics.mentions(null, "幂等")).isFalse();
    }
}
