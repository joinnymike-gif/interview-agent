package com.interviewagent.interview;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 开始面试的请求。输入长度都设了上限：发给模型的每个字都要花钱。
 */
public record StartInterviewRequest(
        @NotBlank @Size(max = 100) String position,
        @NotNull @Min(0) @Max(50) Integer yearsOfExperience,
        @Size(max = StartInterviewRequest.MAX_RESUME_LENGTH) String resume) {

    public static final int MAX_RESUME_LENGTH = 20_000;
}
