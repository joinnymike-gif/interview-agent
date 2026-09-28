package com.interviewagent.interview;

import java.time.Instant;
import java.util.UUID;

/**
 * 一场面试的状态。对话内容本身不在这里，由 ChatMemory 按会话 ID 保存。
 *
 * @param answerCount 候选人已回答的次数
 * @param report      面试结束后生成的评估报告，进行中为 null
 */
public record InterviewSession(
        String id,
        String position,
        int yearsOfExperience,
        String resume,
        Status status,
        int answerCount,
        InterviewReport report,
        Instant createdAt) {

    public enum Status {
        IN_PROGRESS, FINISHED
    }

    static InterviewSession start(String position, int yearsOfExperience, String resume) {
        return new InterviewSession(UUID.randomUUID().toString(), position, yearsOfExperience, resume,
                Status.IN_PROGRESS, 0, null, Instant.now());
    }

    InterviewSession withAnswer() {
        return new InterviewSession(id, position, yearsOfExperience, resume, status, answerCount + 1, report, createdAt);
    }

    InterviewSession finish(InterviewReport report) {
        return new InterviewSession(id, position, yearsOfExperience, resume, Status.FINISHED, answerCount, report, createdAt);
    }
}
