package com.interviewagent.interview;

import com.interviewagent.resume.ResumeParser;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewService interviewService;
    private final ResumeParser resumeParser;

    public InterviewController(InterviewService interviewService, ResumeParser resumeParser) {
        this.interviewService = interviewService;
        this.resumeParser = resumeParser;
    }

    /** 开始面试，返回会话 ID 和面试官的开场白（含第一个问题） */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public InterviewReply start(@Valid @RequestBody StartInterviewRequest request) {
        return interviewService.start(request);
    }

    /** 上传简历 PDF 开始面试：表单字段 position、yearsOfExperience，文件字段 resumeFile */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InterviewReply startWithResume(@Valid @ModelAttribute StartInterviewRequest request,
                                          @RequestPart MultipartFile resumeFile) {
        String resume = resumeParser.parse(resumeFile);
        return interviewService.start(new StartInterviewRequest(request.position(), request.yearsOfExperience(), resume));
    }

    /** 提交回答，返回面试官的下一句（追问或新问题） */
    @PostMapping("/{id}/answers")
    public InterviewReply answer(@PathVariable String id, @Valid @RequestBody AnswerRequest request) {
        return interviewService.answer(id, request.answer());
    }

    /** 提交回答，以 SSE 流式返回面试官的回复 */
    @PostMapping(value = "/{id}/answers/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> answerStream(@PathVariable String id, @Valid @RequestBody AnswerRequest request) {
        return interviewService.answerStream(id, request.answer());
    }

    /** 结束面试，返回结构化评估报告 */
    @PostMapping("/{id}/finish")
    public InterviewReport finish(@PathVariable String id) {
        return interviewService.finish(id);
    }

    /** 导出面试记录，补上人工打分就能加进评估集（见 README 的"评估集"一节） */
    @GetMapping("/{id}/transcript")
    public InterviewTranscript transcript(@PathVariable String id) {
        return interviewService.transcript(id);
    }

    /** 查询面试状态（结束后包含评估报告） */
    @GetMapping("/{id}")
    public InterviewSession get(@PathVariable String id) {
        return interviewService.get(id);
    }
}
