package com.interviewagent.interview;

import com.interviewagent.interview.TranscriptEntry.Role;
import com.interviewagent.questionbank.Question;
import com.interviewagent.questionbank.QuestionMatcher;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 评估官：根据面试记录生成评估报告（LLM-as-judge）。
 * <p>
 * 先把面试官问过的问题对应回题库，取出考察要点作为评分依据，再让模型对照打分。
 * 打分和人工是否一致，见评估集 GradingEvalTest。
 */
@Component
public class InterviewEvaluator {

    private final ChatClient evaluator;
    private final QuestionMatcher questionMatcher;

    public InterviewEvaluator(@Qualifier("evaluatorChatClient") ChatClient evaluator, QuestionMatcher questionMatcher) {
        this.evaluator = evaluator;
        this.questionMatcher = questionMatcher;
    }

    public InterviewReport evaluate(String position, int yearsOfExperience, List<TranscriptEntry> transcript) {
        return evaluate(position, yearsOfExperience, transcript, findReferences(transcript));
    }

    /**
     * 用指定的参考资料生成报告。传空列表就是不带参考资料，评估集用它对比两种做法的效果。
     */
    public InterviewReport evaluate(String position, int yearsOfExperience, List<TranscriptEntry> transcript,
                                    List<Question> references) {
        return evaluator.prompt()
                .system(s -> s
                        .param("position", position)
                        .param("years", yearsOfExperience))
                .user(evaluationInput(transcript, references))
                .call()
                .entity(InterviewReport.class);
    }

    /** RAG：把面试官问过的问题对应回题库，取出考察要点 */
    public List<Question> findReferences(List<TranscriptEntry> transcript) {
        return questionMatcher.match(interviewerQuestions(transcript));
    }

    /**
     * 评估官的输入：面试记录和参考资料分别放在标签里，方便提示词引用，也把数据和指令分开。
     */
    static String evaluationInput(List<TranscriptEntry> transcript, List<Question> references) {
        String referenceText = references.isEmpty()
                ? "（没有检索到相关的题库题目）"
                : references.stream()
                        .map(q -> "题目：%s\n考察要点：%s".formatted(q.question(), String.join("；", q.keyPoints())))
                        .collect(Collectors.joining("\n\n"));
        return """
                <transcript>
                %s
                </transcript>

                <references>
                %s
                </references>""".formatted(format(transcript), referenceText);
    }

    static List<String> interviewerQuestions(List<TranscriptEntry> transcript) {
        return transcript.stream()
                .filter(e -> e.role() == Role.INTERVIEWER)
                .map(TranscriptEntry::text)
                .filter(StringUtils::hasText)
                .toList();
    }

    static String format(List<TranscriptEntry> transcript) {
        return transcript.stream()
                .map(e -> (e.role() == Role.CANDIDATE ? "候选人：" : "面试官：") + e.text())
                .collect(Collectors.joining("\n\n"));
    }
}
