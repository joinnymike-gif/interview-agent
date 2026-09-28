package com.interviewagent.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewagent.interview.TranscriptEntry.Role;
import com.interviewagent.questionbank.Question;
import com.interviewagent.questionbank.Question.Difficulty;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

class EvaluationInputTest {

    private final List<TranscriptEntry> transcript = List.of(
            new TranscriptEntry(Role.CANDIDATE, InterviewService.KICKOFF_MESSAGE),
            new TranscriptEntry(Role.INTERVIEWER, "请讲讲 G1 收集器的原理。"),
            new TranscriptEntry(Role.CANDIDATE, "G1 把堆分成多个 Region"),
            new TranscriptEntry(Role.INTERVIEWER, "Mixed GC 回收哪些 Region？"));

    @Test
    void wrapsTranscriptAndReferencesInTags() {
        var g1 = new Question("jvm-002", "JVM", Difficulty.HARD, "G1 收集器的工作原理是什么？",
                List.of("Region 化内存布局", "Young GC / Mixed GC"));

        String input = InterviewEvaluator.evaluationInput(transcript, List.of(g1));

        assertThat(input).isEqualTo("""
                <transcript>
                候选人：%s

                面试官：请讲讲 G1 收集器的原理。

                候选人：G1 把堆分成多个 Region

                面试官：Mixed GC 回收哪些 Region？
                </transcript>

                <references>
                题目：G1 收集器的工作原理是什么？
                考察要点：Region 化内存布局；Young GC / Mixed GC
                </references>""".formatted(InterviewService.KICKOFF_MESSAGE));
    }

    @Test
    void saysSoWhenNothingWasRetrieved() {
        assertThat(InterviewEvaluator.evaluationInput(transcript, List.of()))
                .contains("<references>\n（没有检索到相关的题库题目）\n</references>");
    }

    @Test
    void usesOnlyInterviewerMessagesAsQueries() {
        assertThat(InterviewEvaluator.interviewerQuestions(transcript))
                .containsExactly("请讲讲 G1 收集器的原理。", "Mixed GC 回收哪些 Region？");
    }

    @Test
    void convertsChatMemoryToTranscript() {
        var entries = TranscriptEntry.fromMessages(List.of(
                new SystemMessage("系统提示词不应出现在面试记录里"),
                new UserMessage("你好"),
                new AssistantMessage("请讲讲 G1。")));

        assertThat(entries).containsExactly(
                new TranscriptEntry(Role.CANDIDATE, "你好"),
                new TranscriptEntry(Role.INTERVIEWER, "请讲讲 G1。"));
    }
}
