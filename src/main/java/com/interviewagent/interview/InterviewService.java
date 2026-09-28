package com.interviewagent.interview;

import com.interviewagent.config.InterviewProperties;
import com.interviewagent.interview.InterviewSession.Status;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
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
    private final InterviewEvaluator evaluator;
    private final ChatMemory chatMemory;
    private final InterviewSessionStore sessionStore;
    private final InterviewProperties properties;

    public InterviewService(@Qualifier("interviewerChatClient") ChatClient interviewer,
                            InterviewEvaluator evaluator,
                            ChatMemory chatMemory,
                            InterviewSessionStore sessionStore,
                            InterviewProperties properties) {
        this.interviewer = interviewer;
        this.evaluator = evaluator;
        this.chatMemory = chatMemory;
        this.sessionStore = sessionStore;
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

        InterviewReport report = evaluator.evaluate(
                session.position(), session.yearsOfExperience(), TranscriptEntry.fromMessages(chatMemory.get(sessionId)));

        sessionStore.update(sessionId, s -> s.finish(report));
        return report;
    }

    public InterviewSession get(String sessionId) {
        return sessionStore.get(sessionId);
    }

    /**
     * 导出面试记录，格式和评估集的用例一致，补上人工打分就能加进评估集。
     */
    public InterviewTranscript transcript(String sessionId) {
        InterviewSession session = sessionStore.get(sessionId);
        return new InterviewTranscript(session.position(), session.yearsOfExperience(),
                TranscriptEntry.fromMessages(chatMemory.get(sessionId)));
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
}
