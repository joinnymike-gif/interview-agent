package com.interviewagent.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interviewagent.TestPdfs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ResumeParserTest {

    private final ResumeParser parser = new ResumeParser();

    @Test
    void extractsTextFromPdf() throws Exception {
        byte[] pdf = TestPdfs.withLines("Zhang San - Java Developer", "Built an order system with Kafka and Redis");

        String text = parser.parse(pdf("resume.pdf", pdf));

        assertThat(text).isEqualTo("Zhang San - Java Developer\nBuilt an order system with Kafka and Redis");
    }

    @Test
    void rejectsPdfWithoutText() throws Exception {
        assertBadRequest(pdf("scan.pdf", TestPdfs.withLines()), "没有从 PDF 中提取到文字");
    }

    @Test
    void rejectsNonPdfFile() {
        assertBadRequest(pdf("resume.docx", "not a pdf".getBytes()), "无法解析简历文件");
    }

    @Test
    void rejectsEmptyFile() {
        assertBadRequest(pdf("resume.pdf", new byte[0]), "简历文件为空");
    }

    @Test
    void normalizeCollapsesBlankLinesAndTrims() {
        assertThat(ResumeParser.normalize("  Java  \n\n\n\n  Redis \r\n")).isEqualTo("Java\n\nRedis");
    }

    private static MockMultipartFile pdf(String filename, byte[] content) {
        return new MockMultipartFile("resumeFile", filename, "application/pdf", content);
    }

    private void assertBadRequest(MockMultipartFile file, String message) {
        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains(message);
                });
    }
}
