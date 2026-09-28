package com.interviewagent.interview;

import com.interviewagent.interview.InterviewReport.Recommendation;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * 评估集用到的统计指标：衡量评估官（模型）的打分和人工打分有多一致。
 */
final class GradingMetrics {

    private GradingMetrics() {
    }

    /** 平均绝对误差：平均每份记录差多少分 */
    static double meanAbsoluteError(List<Double> predicted, List<Double> actual) {
        return IntStream.range(0, predicted.size())
                .mapToDouble(i -> Math.abs(predicted.get(i) - actual.get(i)))
                .average().orElse(Double.NaN);
    }

    /** 两个录用建议相差几档，例如 STRONG_HIRE 和 LEAN_HIRE 差 2 档 */
    static int distance(Recommendation a, Recommendation b) {
        return Math.abs(a.ordinal() - b.ordinal());
    }

    /**
     * Spearman 等级相关系数：只看排序是否一致，不看具体分数，范围 -1 到 1，1 表示排序完全一致。
     * 模型打分可能整体偏高或偏低，但只要把好的候选人排在前面，排序相关就高。
     */
    static double spearman(List<Double> x, List<Double> y) {
        return pearson(ranks(x), ranks(y));
    }

    /**
     * missedPoints 里有没有提到某个要点。关键词用 | 分隔表示多种说法，命中任意一种即可，忽略大小写。
     */
    static boolean mentions(List<String> missedPoints, String keywords) {
        String text = String.join("\n", missedPoints == null ? List.of() : missedPoints).toLowerCase(Locale.ROOT);
        return Arrays.stream(keywords.split("\\|"))
                .map(k -> k.strip().toLowerCase(Locale.ROOT))
                .anyMatch(text::contains);
    }

    /** 名次（从 1 开始），并列的取平均名次 */
    static double[] ranks(List<Double> values) {
        Integer[] order = IntStream.range(0, values.size()).boxed().toArray(Integer[]::new);
        Arrays.sort(order, Comparator.comparingDouble(values::get));
        double[] ranks = new double[values.size()];
        for (int i = 0; i < order.length; ) {
            int j = i;
            while (j + 1 < order.length && values.get(order[j + 1]).equals(values.get(order[i]))) {
                j++;
            }
            double averageRank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) {
                ranks[order[k]] = averageRank;
            }
            i = j + 1;
        }
        return ranks;
    }

    private static double pearson(double[] x, double[] y) {
        double meanX = Arrays.stream(x).average().orElse(Double.NaN);
        double meanY = Arrays.stream(y).average().orElse(Double.NaN);
        double cov = 0;
        double varX = 0;
        double varY = 0;
        for (int i = 0; i < x.length; i++) {
            cov += (x[i] - meanX) * (y[i] - meanY);
            varX += (x[i] - meanX) * (x[i] - meanX);
            varY += (y[i] - meanY) * (y[i] - meanY);
        }
        return varX == 0 || varY == 0 ? Double.NaN : cov / Math.sqrt(varX * varY);
    }
}
