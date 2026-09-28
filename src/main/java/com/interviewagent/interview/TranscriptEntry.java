package com.interviewagent.interview;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * 面试记录中的一句话。
 */
public record TranscriptEntry(Role role, String text) {

    public enum Role {
        INTERVIEWER, CANDIDATE
    }

    /** 从对话记忆转换：模型说的是面试官，用户说的是候选人，其他类型的消息忽略 */
    static List<TranscriptEntry> fromMessages(List<Message> messages) {
        return messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .map(m -> new TranscriptEntry(
                        m.getMessageType() == MessageType.USER ? Role.CANDIDATE : Role.INTERVIEWER, m.getText()))
                .toList();
    }
}
