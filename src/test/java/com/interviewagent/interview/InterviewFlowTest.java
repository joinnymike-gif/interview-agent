package com.interviewagent.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.interviewagent.StubChatModel;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 用假模型跑通完整的面试流程，不需要 API Key，也不产生费用。
 */
@SpringBootTest(properties = "spring.ai.model.chat=none")
@AutoConfigureMockMvc
class InterviewFlowTest {

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StubChatModel chatModel;

    @Test
    void fullInterviewFlow() throws Exception {
        String sessionId = startInterview();

        // 系统提示词应填入岗位、年限和简历，并带上题库工具
        String systemPrompt = chatModel.lastPrompt().getSystemMessage().getText();
        assertThat(systemPrompt).contains("Java 后端开发", "5 年工作经验", "负责订单系统");
        var options = (ToolCallingChatOptions) chatModel.lastPrompt().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(cb -> cb.getToolDefinition().name())
                .containsExactlyInAnyOrder("listTopics", "searchQuestions");

        // 回答里带花括号的代码片段，不能被当成模板变量
        mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "可以用 map.computeIfAbsent(key, k -> new ArrayList<>())，{} 是空代码块"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("面试官回复 2"));

        // 对话记忆：第二次请求应带上第一轮的对话
        assertThat(chatModel.lastPrompt().getInstructions())
                .extracting(Message::getText)
                .contains(InterviewService.KICKOFF_MESSAGE, "面试官回复 1");

        mockMvc.perform(post("/api/interviews/{id}/finish", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(72))
                .andExpect(jsonPath("$.recommendation").value("HIRE"))
                .andExpect(jsonPath("$.questionReviews[0].referenceAnswer").value("数组 + 链表 + 红黑树"));

        // 评估官收到的是整理成文本的面试记录
        assertThat(chatModel.lastPrompt().getUserMessage().getText())
                .contains("候选人：" + InterviewService.KICKOFF_MESSAGE, "面试官：面试官回复 1", "候选人：可以用");

        mockMvc.perform(get("/api/interviews/{id}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.answerCount").value(1))
                .andExpect(jsonPath("$.report.overallScore").value(72));

        // 结束后不能再回答
        mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answer": "再补充一点"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("面试已结束"));
    }

    @Test
    void streamingAnswerIsSentAsServerSentEvents() throws Exception {
        String sessionId = startInterview();

        MvcResult result = mockMvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"answer": "HashMap 底层是数组加链表"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        // SSE 规范要求按 UTF-8 解码，MockMvc 默认按 ISO-8859-1，这里显式指定
        String events = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(events).contains("data:流式", "data:回复");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            """
            {"position": "", "yearsOfExperience": 5}""",
            """
            {"position": "Java 后端开发"}""",
            """
            {"position": "Java 后端开发", "yearsOfExperience": -1}"""
    })
    void rejectsInvalidStartRequest(String body) throws Exception {
        mockMvc.perform(post("/api/interviews").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request content."));
    }

    @Test
    void unknownInterviewReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/interviews/{id}", "no-such-id"))
                .andExpect(status().isNotFound());
    }

    private String startInterview() throws Exception {
        String body = mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position": "Java 后端开发", "yearsOfExperience": 5, "resume": "负责订单系统"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }
}
