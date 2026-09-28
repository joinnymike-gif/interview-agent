package com.interviewagent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;

/**
 * 启动完整 Spring 容器的测试。使用 test 配置（见 application-test.yml），
 * 不连数据库、不调 embedding 接口。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@Import(TestAiConfig.class)
public @interface IntegrationTest {

    @AliasFor(annotation = SpringBootTest.class)
    String[] properties() default {};
}
