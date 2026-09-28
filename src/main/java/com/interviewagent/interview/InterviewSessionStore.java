package com.interviewagent.interview;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 面试会话存储。先用内存实现，重启即丢失；上生产时换成数据库或 Redis。
 */
@Component
public class InterviewSessionStore {

    private final Map<String, InterviewSession> sessions = new ConcurrentHashMap<>();

    public InterviewSession save(InterviewSession session) {
        sessions.put(session.id(), session);
        return session;
    }

    public InterviewSession get(String id) {
        InterviewSession session = sessions.get(id);
        if (session == null) {
            throw notFound(id);
        }
        return session;
    }

    /**
     * 原子地更新会话。updater 里抛出的异常会原样抛出，会话保持不变。
     */
    public InterviewSession update(String id, UnaryOperator<InterviewSession> updater) {
        InterviewSession updated = sessions.computeIfPresent(id, (key, session) -> updater.apply(session));
        if (updated == null) {
            throw notFound(id);
        }
        return updated;
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "面试不存在：" + id);
    }
}
