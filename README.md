# interview-agent

AI 模拟面试官。基于 Spring AI 的练手项目，覆盖 AI 应用开发的几个核心技能：提示词模板、对话记忆、工具调用、结构化输出、流式输出，以及不依赖真实模型的测试。

## 功能

- 按岗位、工作年限和简历开始一场面试
- 面试官每次只问一个问题，根据回答决定追问还是换题
- 出题前通过工具调用（Function Calling）查询题库，再结合候选人背景挑选或改写题目
- 支持普通返回和 SSE 流式返回
- 结束后生成结构化评估报告：总分、录用建议、分维度评分、逐题复盘（附参考答案要点）、改进建议

## 技术栈

- Java 21、Spring Boot 4.1、Spring AI 2.0
- 模型：默认 DeepSeek（`deepseek-chat`），也可以换成其他 OpenAI 兼容接口（如通义千问）或 Anthropic Claude，只改配置不改代码

## 快速开始

需要 JDK 21。不用装 Maven，项目自带 `./mvnw`。

**使用 DeepSeek（默认）：**

```bash
export CHAT_API_KEY=sk-xxx          # DeepSeek 开放平台的 API Key
./mvnw spring-boot:run
```

**换成通义千问：**

```bash
export CHAT_API_KEY=sk-xxx          # 阿里云百炼的 API Key
export CHAT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export CHAT_MODEL=qwen-plus
./mvnw spring-boot:run
```

**换成 Claude：**

```bash
export AI_PROVIDER=anthropic
export ANTHROPIC_API_KEY=sk-ant-xxx
./mvnw spring-boot:run
```

DeepSeek、通义千问走的都是 OpenAI 兼容协议，实际请求地址是 `CHAT_BASE_URL` + `/chat/completions`。

### 试一下

```bash
# 1. 开始面试，记下返回的 sessionId
curl -s -X POST localhost:8080/api/interviews \
  -H 'Content-Type: application/json' \
  -d '{"position": "Java 后端开发", "yearsOfExperience": 5, "resume": "负责电商订单系统，日均订单 50 万"}'

# 2. 回答问题（普通返回）
curl -s -X POST localhost:8080/api/interviews/{sessionId}/answers \
  -H 'Content-Type: application/json' \
  -d '{"answer": "HashMap 底层是数组加链表，JDK 8 以后链表过长会转成红黑树……"}'

# 3. 回答问题（流式返回，逐字输出）
curl -N -X POST localhost:8080/api/interviews/{sessionId}/answers/stream \
  -H 'Content-Type: application/json' \
  -d '{"answer": "我会先看 GC 日志……"}'

# 4. 结束面试，生成评估报告
curl -s -X POST localhost:8080/api/interviews/{sessionId}/finish

# 5. 查询面试状态（结束后包含报告）
curl -s localhost:8080/api/interviews/{sessionId}
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/interviews` | 开始面试，返回 `sessionId` 和开场白（含第一个问题） |
| POST | `/api/interviews/{id}/answers` | 提交回答，返回面试官的下一句 |
| POST | `/api/interviews/{id}/answers/stream` | 同上，以 SSE 流式返回 |
| POST | `/api/interviews/{id}/finish` | 结束面试，返回评估报告；重复调用直接返回已生成的报告 |
| GET | `/api/interviews/{id}` | 查询面试状态 |

错误响应统一为 ProblemDetail 格式：会话不存在返回 404，已结束或超出回答次数返回 409，参数校验失败返回 400。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `AI_PROVIDER` | `openai` | `openai`（OpenAI 兼容接口，如 DeepSeek、通义千问）或 `anthropic` |
| `CHAT_API_KEY` | | 对话模型的 API Key |
| `CHAT_BASE_URL` | `https://api.deepseek.com` | 对话模型的接口地址 |
| `CHAT_MODEL` | `deepseek-chat` | 对话模型名，以各厂商文档为准 |
| `ANTHROPIC_API_KEY` | | `AI_PROVIDER=anthropic` 时使用 |
| `ANTHROPIC_MODEL` | `claude-opus-5` | `AI_PROVIDER=anthropic` 时使用 |

面试相关参数在 `application.yml` 的 `interview.*` 下：主问题数量、单场最多回答次数、记忆保留条数。

想看每次发给模型的完整请求和响应，把 `logging.level.org.springframework.ai.chat.client.advisor` 改成 `DEBUG`。

## 请求是怎么流转的

```
POST /api/interviews/{id}/answers
  └─ InterviewService
       └─ interviewerChatClient
            ├─ 系统提示词：prompts/interviewer-system.st，按会话填入岗位、年限、简历
            ├─ MessageChatMemoryAdvisor：按 sessionId 取出历史对话拼进请求，回复后再存回去
            ├─ QuestionBankTools：模型需要时调用 searchQuestions，Spring AI 执行方法并把结果回传给模型
            └─ ChatModel：默认 DeepSeek，由 AI_PROVIDER 等配置决定

POST /api/interviews/{id}/finish
  └─ InterviewService
       └─ evaluatorChatClient（独立的评估官，没有记忆和工具）
            ├─ 输入：把对话记忆整理成的文本面试记录
            └─ 输出：.entity(InterviewReport.class)，Spring AI 生成 JSON Schema 并把结果反序列化成 Java 对象
```

## 代码导读

建议按下表顺序读，每一行对应一个 AI 应用开发的知识点：

| 知识点 | 看哪里 |
|---|---|
| ChatClient 与提示词模板 | `config/AiConfig`、`resources/prompts/*.st`、`InterviewService#interviewerPrompt` |
| 对话记忆 | `AiConfig#chatMemory`、`MessageChatMemoryAdvisor` |
| 工具调用 | `questionbank/QuestionBankTools` |
| 结构化输出 | `interview/InterviewReport`、`InterviewService#finish` |
| 流式输出 | `InterviewController#answerStream` |
| 成本控制 | `StartInterviewRequest` 的输入长度限制、`interview.max-answers` |
| 不依赖真实模型的测试 | `src/test/.../StubChatModel`、`InterviewFlowTest` |

## 项目结构

```
src/main/java/com/interviewagent
├── InterviewAgentApplication.java
├── config
│   ├── AiConfig.java              # ChatClient、ChatMemory 等 Bean
│   └── InterviewProperties.java   # interview.* 配置
├── interview
│   ├── InterviewController.java   # REST 接口
│   ├── InterviewService.java      # 面试流程
│   ├── InterviewSession.java      # 会话状态
│   ├── InterviewSessionStore.java # 会话存储（内存）
│   ├── InterviewReport.java       # 评估报告（结构化输出）
│   └── ...Request / Reply         # 请求和响应 DTO
└── questionbank
    ├── Question.java
    ├── QuestionBank.java          # 从 question-bank.json 加载题库
    └── QuestionBankTools.java     # 暴露给模型的工具
src/main/resources
├── application.yml
├── question-bank.json             # 题库，可自行扩充
└── prompts
    ├── interviewer-system.st      # 面试官系统提示词
    └── evaluator-system.st        # 评估官系统提示词
```

## 测试

```bash
./mvnw test
```

测试用 `StubChatModel` 代替真实模型，不需要 API Key，也不产生费用。覆盖完整面试流程、对话记忆、流式输出、参数校验、题库工具的参数 Schema，以及两种模型厂商的装配。

## 已知限制

- 会话和对话记忆都存在内存里，重启就丢失。
- 没有鉴权。不要直接暴露到公网，否则任何人都能调用接口，花的是你的 API 额度。
- 评估报告靠提示词约束模型输出 JSON，而不是厂商的原生 JSON Schema 模式（DeepSeek 不支持后者）。模型偶尔可能输出不合法的 JSON，导致生成报告失败，重试即可。
- 切换到 Claude 时：Claude API 提供服务端 `fallbacks` 参数（请求被安全分类器拒绝时自动换模型重试），Spring AI 2.0.1 的 Anthropic 配置项里还没有这个参数，所以没有启用。面试场景一般不会触发拒绝。

## 下一步

- [ ] RAG：解析简历 PDF，把题库向量化存进 pgvector，按简历内容语义检索相关题目
- [ ] 评估集：收集一批面试记录并人工打分，检验评估官的打分和人工是否一致（LLM-as-judge 校准）
- [ ] 持久化：会话存数据库，对话记忆换成 `JdbcChatMemoryRepository` 或 Redis
- [ ] 可观测性：统计每场面试的 token 用量和成本，接入链路追踪
- [ ] 前端：聊天界面和报告展示页
- [ ] 语音面试：语音识别 + 语音合成
