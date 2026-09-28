package com.interviewagent.questionbank;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把面试官实际问过的问题对应回题库，为评估提供考察要点作为评分依据。
 * <p>
 * 面试官出题时可能改写了题库的题目，所以不按题目文字精确匹配，而是拿每一句提问去检索，
 * 复用和出题时同一套检索流程（混合检索 + rerank）。
 */
@Component
public class QuestionMatcher {

    private static final Logger log = LoggerFactory.getLogger(QuestionMatcher.class);

    private final QuestionRetriever retriever;

    public QuestionMatcher(QuestionRetriever retriever) {
        this.retriever = retriever;
    }

    /**
     * 每句提问取最相关的一道题，去重后按首次出现的顺序返回。
     * <p>
     * 寒暄、追问这类消息也会检索到某道题，这里不做过滤：相关度分数在不同请求之间没法直接比较，
     * 定一个阈值容易误伤。交给评估官判断参考题和实际提问是否对得上。
     * <p>
     * 检索出错时返回空列表：参考资料只是让评分更有依据，不能因为它失败就生成不了报告。
     */
    public List<Question> match(List<String> askedQuestions) {
        Map<String, Question> matched = new LinkedHashMap<>();
        try {
            for (String asked : askedQuestions) {
                retriever.search(asked, null).stream().findFirst().ifPresent(q -> matched.putIfAbsent(q.id(), q));
            }
        } catch (RuntimeException e) {
            log.warn("检索评估参考资料失败，本次评估不带参考资料：{}", e.toString());
            return List.of();
        }
        log.info("评估参考了 {} 道题库题目：{}", matched.size(), matched.keySet());
        return List.copyOf(matched.values());
    }
}
