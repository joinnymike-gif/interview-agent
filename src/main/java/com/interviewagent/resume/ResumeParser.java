package com.interviewagent.resume;

import com.interviewagent.interview.StartInterviewRequest;
import java.io.IOException;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 从简历 PDF 中提取纯文本。
 * <p>
 * 简历通常只有一两页，直接整份放进提示词即可，不需要切分和向量化。
 * 只提取文字、不保留排版空格，可以少花一些 token。
 */
@Component
public class ResumeParser {

    public String parse(MultipartFile file) {
        if (file.isEmpty()) {
            throw badRequest("简历文件为空");
        }
        String text;
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            text = new PDFTextStripper().getText(document);
        } catch (InvalidPasswordException e) {
            throw badRequest("简历 PDF 有密码保护，请去掉密码后再上传");
        } catch (IOException e) {
            throw badRequest("无法解析简历文件，请上传 PDF 格式");
        }

        text = normalize(text);
        if (text.isEmpty()) {
            throw badRequest("没有从 PDF 中提取到文字，可能是扫描件或图片，请改为直接粘贴简历文本");
        }
        if (text.length() > StartInterviewRequest.MAX_RESUME_LENGTH) {
            throw badRequest("简历内容过长（超过 %d 字），请精简后再上传".formatted(StartInterviewRequest.MAX_RESUME_LENGTH));
        }
        return text;
    }

    /** 去掉每行首尾空白，多个连续空行合并成一个 */
    static String normalize(String text) {
        return text.lines()
                .map(String::strip)
                .collect(Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
