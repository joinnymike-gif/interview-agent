package com.interviewagent.interview;

import java.util.List;

/**
 * 一场面试的完整记录。格式和评估集（src/test/resources/eval/grading-cases.json）里的用例一致，
 * 导出后补上人工打分就能加进评估集。
 */
public record InterviewTranscript(String position, int yearsOfExperience, List<TranscriptEntry> transcript) {
}
