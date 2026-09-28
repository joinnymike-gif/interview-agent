package com.interviewagent.interview;

import com.interviewagent.config.InterviewProperties;
import com.interviewagent.interview.InterviewSession.Status;
import com.interviewagent.questionbank.Question;
import com.interviewagent.questionbank.QuestionMatcher;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@Service
public class InterviewService {

    /** 开场时代候选人发出的第一句话，让面试官开始提问 */
    static final String KICKOFF_MESSAGE = "你好，我准备好了，请开始面试。";

    private final ChatClient interviewer;
    private final ChatClient evaluator;
    private final ChatMemory chatMemory;
    private final InterviewSessionStore sessionStore;
    private final QuestionMatcher questionMatcher;
    private final InterviewProperties properties;

    public InterviewService(@Qualifier("interviewerChatClient") ChatClient interviewer,
                            @Qualifier("evaluatorChatClient") ChatClient evaluator,
                            ChatMemory chatMemory,
                            InterviewSessionStore sessionStore,
                            QuestionMatcher questionMatcher,
                            InterviewProperties properties) {
        this.interviewer = interviewer;
        this.evaluator = evaluator;
        this.chatMemory = chatMemory;
        this.sessionStore = sessionStore;
        this.questionMatcher = questionMatcher;
        this.properties = properties;
    }

    public InterviewReply start(StartInterviewRequest request) {
        InterviewSession session = sessionStore.save(
                InterviewSession.start(request.position(), request.yearsOfExperience(), request.resume()));
        String reply = interviewerPrompt(session, KICKOFF_MESSAGE).call().content();
        return new InterviewReply(session.id(), reply);
    }

    public InterviewReply answer(String sessionId, String answer) {
        InterviewSession session = recordAnswer(sessionId);
        String reply = interviewerPrompt(session, answer).call().content();
        return new InterviewReply(sessionId, reply);
    }

    /**
     * 流式版本：模型边生成边返回，前端可以逐字显示，用户不用干等整段回复。
     */
    public Flux<String> answerStream(String sessionId, String answer) {
        InterviewSession session = recordAnswer(sessionId);
        return interviewerPrompt(session, answer).stream().content();
    }

    /**
     * 结束面试并生成评估报告。重复调用直接返回已生成的报告，不会再次调用模型。
     */
    public InterviewReport finish(String sessionId) {
        InterviewSession session = sessionStore.get(sessionId);
        if (session.status() == Status.FINISHED) {
            return session.report();
        }

        List<Message> history = chatMemory.get(sessionId);
        // RAG：把面试官问过的问题对应回题库，取出考察要点作为评分依据
        List<Question> references = questionMatcher.match(interviewerMessages(history));

        InterviewReport report = evaluator.prompt()
                .system(s -> s
                        .param("position", session.position())
                        .param("years", session.yearsOfExperience()))
                .user(evaluationInput(history, references))
                .call()
                .entity(InterviewReport.class);

        sessionStore.update(sessionId, s -> s.finish(report));
        return report;
    }

    public InterviewSession get(String sessionId) {
        return sessionStore.get(sessionId);
    }

    private ChatClient.ChatClientRequestSpec interviewerPrompt(InterviewSession session, String userMessage) {
        String resume = StringUtils.hasText(session.resume()) ? session.resume() : "（未提供）";
        return interviewer.prompt()
                .system(s -> s
                        .param("position", session.position())
                        .param("years", session.yearsOfExperience())
                        .param("maxQuestions", properties.maxQuestions())
                        .param("resume", resume))
                .user(userMessage)
                // 用会话 ID 区分不同面试的对话记忆
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, session.id()));
    }

    private InterviewSession recordAnswer(String sessionId) {
        return sessionStore.update(sessionId, session -> {
            if (session.status() == Status.FINISHED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "面试已结束");
            }
            if (session.answerCount() >= properties.maxAnswers()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "已达到本场面试的最大回答次数，请结束面试查看报告");
            }
            return session.withAnswer();
        });
    }

    /**
     * 评估官的输入：面试记录和参考资料分别放在标签里，方便提示词引用，也把数据和指令分开。
     */
    static String evaluationInput(List<Message> history, List<Question> references) {
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
                </references>""".formatted(transcript(history), referenceText);
    }

    static List<String> interviewerMessages(List<Message> history) {
        return history.stream()
                .filter(m -> m.getMessageType() == MessageType.ASSISTANT)
                .map(Message::getText)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 把对话记忆整理成纯文本的面试记录，交给评估官。
     */
    static String transcript(List<Message> messages) {
        return messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .map(m -> (m.getMessageType() == MessageType.USER ? "候选人：" : "面试官：") + m.getText())
                .collect(Collectors.joining("\n\n"));
    }
}
