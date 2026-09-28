package com.interviewagent.questionbank;

import java.util.List;

/**
 * 题库中的一道题。
 *
 * @param keyPoints 考察要点，面试官追问和评估时参考
 */
public record Question(String id, String topic, Difficulty difficulty, String question, List<String> keyPoints) {

    public enum Difficulty {
        EASY, MEDIUM, HARD
    }
}
