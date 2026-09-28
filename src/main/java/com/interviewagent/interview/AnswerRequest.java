package com.interviewagent.interview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnswerRequest(@NotBlank @Size(max = 5_000) String answer) {
}
