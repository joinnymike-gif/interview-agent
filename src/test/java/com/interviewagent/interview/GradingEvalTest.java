package com.interviewagent.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewagent.interview.InterviewReport.Recommendation;
import com.interviewagent.questionbank.Question;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntPredicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 评估集：检验评估官（LLM-as-judge）的打分和人工打分是否一致。
 * <p>
 * eval/grading-cases.json 里每条用例是一场面试记录加人工标注（总分、录用建议、应该指出的漏答要点）。
 * 用真实模型给每场面试打分，分别在「带参考资料」和「不带参考资料」两种情况下跑，对比哪种更接近人工。
 * <p>
 * 要调用对话模型、embedding 和 rerank 接口，设置了 CHAT_API_KEY 和 EMBEDDING_API_KEY 才会运行：
 * <pre>
 * CHAT_API_KEY=sk-xxx EMBEDDING_API_KEY=sk-xxx ./mvnw test -Dtest=GradingEvalTest
 * </pre>
 * 模型每次输出不完全一样，设置 EVAL_REPEATS=3 可以让每场面试打 3 次分，看分数波动有多大。
 * 向量库用内存实现，不需要 PostgreSQL。
 */
@EnabledIfEnvironmentVariable(named = "CHAT_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "EMBEDDING_API_KEY", matches = ".+")
@SpringBootTest(properties = {
        "spring.ai.model.embedding=openai",
        "interview.rag.rerank.enabled=${RERANK_ENABLED:true}"})
@ActiveProfiles("test")
class GradingEvalTest {

    /** 线上用的是「带参考资料」，下面的及格线只检查它。第一次用真实模型跑完后，按结果调整 */
    private static final double MAX_MEAN_ABSOLUTE_ERROR = 15;
    private static final double MIN_ADJACENT_AGREEMENT = 0.75;
    private static final double MIN_SUCCESS_RATE = 0.8;

    private static final int REPEATS = Integer.parseInt(System.getenv().getOrDefault("EVAL_REPEATS", "1"));
    private static final int CONCURRENCY = 4;

    enum Variant {
        WITH_REFERENCES("带参考资料"), WITHOUT_REFERENCES("不带参考资料");

        final String label;

        Variant(String label) {
            this.label = label;
        }
    }

    record Human(int overallScore, Recommendation recommendation, List<String> expectedMissedPoints, String rationale) {
    }

    record GradingCase(String id, String description, String position, int yearsOfExperience,
                       List<TranscriptEntry> transcript, Human human) {
    }

    record Pair(String better, String worse, String reason) {
    }

    record Dataset(List<GradingCase> cases, List<Pair> pairs) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
            return SimpleVectorStore.builder(embeddingModel).build();
        }
    }

    @Autowired
    InterviewEvaluator evaluator;

    @Test
    void compareWithHumanScores() throws Exception {
        Dataset dataset;
        try (InputStream in = new ClassPathResource("eval/grading-cases.json").getInputStream()) {
            dataset = JsonMapper.builder().build().readValue(in, new TypeReference<Dataset>() {
            });
        }
        List<GradingCase> cases = dataset.cases();

        // 每场面试只检索一次参考资料，两种做法、多次打分共用，保证对比时只有「带不带参考资料」这一个变量
        Map<String, List<Question>> references = new LinkedHashMap<>();
        for (GradingCase c : cases) {
            references.put(c.id(), evaluator.findReferences(c.transcript()));
        }

        Map<Variant, Map<String, List<InterviewReport>>> reports = new EnumMap<>(Variant.class);
        List<String> failures = new CopyOnWriteArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY)) {
            List<Future<?>> futures = new ArrayList<>();
            for (Variant variant : Variant.values()) {
                Map<String, List<InterviewReport>> byCase = new ConcurrentHashMap<>();
                reports.put(variant, byCase);
                for (GradingCase c : cases) {
                    byCase.put(c.id(), new CopyOnWriteArrayList<>());
                    List<Question> refs = variant == Variant.WITH_REFERENCES ? references.get(c.id()) : List.of();
                    for (int i = 0; i < REPEATS; i++) {
                        futures.add(pool.submit(() -> {
                            try {
                                byCase.get(c.id()).add(
                                        evaluator.evaluate(c.position(), c.yearsOfExperience(), c.transcript(), refs));
                            } catch (RuntimeException e) {
                                failures.add("%s / %s：%s".formatted(c.id(), variant.label, e));
                            }
                        }));
                    }
                }
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        printPerCase(cases, references, reports);
        Map<Variant, Summary> summaries = new EnumMap<>(Variant.class);
        for (Variant variant : Variant.values()) {
            summaries.put(variant, summarize(dataset, reports.get(variant)));
        }
        printSummary(summaries);
        failures.forEach(f -> System.out.println("打分失败：" + f));

        int total = cases.size() * REPEATS * Variant.values().length;
        assertThat(1 - (double) failures.size() / total).as("打分成功率").isGreaterThanOrEqualTo(MIN_SUCCESS_RATE);
        Summary production = summaries.get(Variant.WITH_REFERENCES);
        assertThat(production.meanAbsoluteError()).as("总分平均误差").isLessThanOrEqualTo(MAX_MEAN_ABSOLUTE_ERROR);
        assertThat(production.adjacentAgreement()).as("录用建议相差不超过一档的比例")
                .isGreaterThanOrEqualTo(MIN_ADJACENT_AGREEMENT);
    }

    record Summary(double meanAbsoluteError, double withinTen, double exactAgreement, double adjacentAgreement,
                   double spearman, double missedPointRecall, double pairAccuracy, double averageSpread) {
    }

    private static Summary summarize(Dataset dataset, Map<String, List<InterviewReport>> reports) {
        // 只统计至少成功打分一次的用例；多次打分时总分取平均
        List<GradingCase> scored = dataset.cases().stream().filter(c -> !reports.get(c.id()).isEmpty()).toList();
        List<Double> modelScores = scored.stream().map(c -> meanScore(reports.get(c.id()))).toList();
        List<Double> humanScores = scored.stream().map(c -> (double) c.human().overallScore()).toList();

        List<Integer> distances = new ArrayList<>();
        List<Double> recalls = new ArrayList<>();
        for (GradingCase c : scored) {
            for (InterviewReport r : reports.get(c.id())) {
                distances.add(GradingMetrics.distance(r.recommendation(), c.human().recommendation()));
                List<String> expected = c.human().expectedMissedPoints();
                if (!expected.isEmpty()) {
                    List<String> missed = r.questionReviews() == null ? List.of() : r.questionReviews().stream()
                            .flatMap(q -> q.missedPoints() == null ? Stream.empty() : q.missedPoints().stream())
                            .toList();
                    recalls.add(expected.stream().filter(k -> GradingMetrics.mentions(missed, k)).count()
                            / (double) expected.size());
                }
            }
        }

        Map<String, Double> scoreById = new LinkedHashMap<>();
        for (int i = 0; i < scored.size(); i++) {
            scoreById.put(scored.get(i).id(), modelScores.get(i));
        }
        List<Pair> comparable = dataset.pairs().stream()
                .filter(p -> scoreById.containsKey(p.better()) && scoreById.containsKey(p.worse())).toList();
        double pairAccuracy = comparable.stream()
                .filter(p -> scoreById.get(p.better()) > scoreById.get(p.worse())).count() / (double) comparable.size();

        return new Summary(
                GradingMetrics.meanAbsoluteError(modelScores, humanScores),
                fraction(scored.size(), i -> Math.abs(modelScores.get(i) - humanScores.get(i)) <= 10),
                distances.stream().filter(d -> d == 0).count() / (double) distances.size(),
                distances.stream().filter(d -> d <= 1).count() / (double) distances.size(),
                GradingMetrics.spearman(modelScores, humanScores),
                average(recalls, Double::doubleValue),
                pairAccuracy,
                average(scored, c -> spread(reports.get(c.id()))));
    }

    private static void printPerCase(List<GradingCase> cases, Map<String, List<Question>> references,
                                     Map<Variant, Map<String, List<InterviewReport>>> reports) {
        System.out.printf("%n%-20s %6s %8s %14s %14s   %-11s %-24s %-24s%n",
                "用例", "参考题", "人工分", "带参考资料", "不带参考资料", "人工建议", "带参考资料建议", "不带参考资料建议");
        for (GradingCase c : cases) {
            List<InterviewReport> with = reports.get(Variant.WITH_REFERENCES).get(c.id());
            List<InterviewReport> without = reports.get(Variant.WITHOUT_REFERENCES).get(c.id());
            System.out.printf("%-20s %6d %8d %14s %14s   %-11s %-24s %-24s%n",
                    c.id(), references.get(c.id()).size(), c.human().overallScore(),
                    scores(with), scores(without), c.human().recommendation(),
                    recommendations(with), recommendations(without));
        }
    }

    private static void printSummary(Map<Variant, Summary> summaries) {
        Summary with = summaries.get(Variant.WITH_REFERENCES);
        Summary without = summaries.get(Variant.WITHOUT_REFERENCES);
        System.out.printf("%n%-28s %12s %14s%n", "指标", "带参考资料", "不带参考资料");
        row("总分平均误差（越小越好）", with.meanAbsoluteError(), without.meanAbsoluteError());
        row("总分误差 ≤ 10 分的比例", with.withinTen(), without.withinTen());
        row("录用建议完全一致", with.exactAgreement(), without.exactAgreement());
        row("录用建议相差不超过一档", with.adjacentAgreement(), without.adjacentAgreement());
        row("排序一致性（Spearman）", with.spearman(), without.spearman());
        row("漏答要点召回率", with.missedPointRecall(), without.missedPointRecall());
        row("成对比较正确率", with.pairAccuracy(), without.pairAccuracy());
        if (REPEATS > 1) {
            row("同一场面试多次打分的平均极差", with.averageSpread(), without.averageSpread());
        }
    }

    private static void row(String name, double with, double without) {
        System.out.printf("%-28s %12.2f %14.2f%n", name, with, without);
    }

    private static String scores(List<InterviewReport> reports) {
        if (reports.isEmpty()) {
            return "失败";
        }
        return reports.stream().map(r -> String.valueOf(r.overallScore())).reduce((a, b) -> a + "/" + b).orElseThrow();
    }

    private static String recommendations(List<InterviewReport> reports) {
        return reports.isEmpty() ? "失败"
                : reports.stream().map(r -> String.valueOf(r.recommendation())).reduce((a, b) -> a + "/" + b).orElseThrow();
    }

    private static double meanScore(List<InterviewReport> reports) {
        return average(reports, InterviewReport::overallScore);
    }

    /** 同一场面试多次打分的最高分减最低分 */
    private static double spread(List<InterviewReport> reports) {
        var stats = reports.stream().mapToInt(InterviewReport::overallScore).summaryStatistics();
        return stats.getMax() - stats.getMin();
    }

    private static <T> double average(Collection<T> items, ToDoubleFunction<T> value) {
        return items.stream().mapToDouble(value).average().orElse(Double.NaN);
    }

    private static double fraction(int n, IntPredicate predicate) {
        return IntStream.range(0, n).filter(predicate).count() / (double) n;
    }
}
