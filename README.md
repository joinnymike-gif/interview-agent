# interview-agent

AI 模拟面试官。基于 Spring AI 的练手项目，覆盖 AI 应用开发的几个核心技能：提示词模板、对话记忆、工具调用、结构化输出、流式输出、RAG（向量检索），以及不依赖真实模型的测试和检索效果评估。

## 功能

- 按岗位、工作年限和简历开始一场面试，简历可以直接上传 PDF
- 面试官每次只问一个问题，根据回答决定追问还是换题
- 出题前通过工具调用在题库里做语义检索，结合简历里的项目和技术栈挑选或改写题目
- 支持普通返回和 SSE 流式返回
- 结束后生成结构化评估报告：总分、录用建议、分维度评分、逐题复盘（附参考答案要点）、改进建议

## 技术栈

- Java 21、Spring Boot 4.1、Spring AI 2.0
- 对话模型：默认 DeepSeek（`deepseek-chat`），也可以换成其他 OpenAI 兼容接口（如通义千问）或 Anthropic Claude，只改配置不改代码
- 向量模型：默认通义千问 `text-embedding-v4`（DeepSeek 没有 embedding 接口）
- 向量库：PostgreSQL + pgvector

## 快速开始

需要 JDK 21 和 Docker。不用装 Maven，项目自带 `./mvnw`。

需要两个 API Key：

- **对话模型**：DeepSeek 开放平台的 API Key
- **向量模型**：阿里云百炼（通义千问）的 API Key

```bash
export CHAT_API_KEY=sk-xxx          # DeepSeek
export EMBEDDING_API_KEY=sk-xxx     # 阿里云百炼
./mvnw spring-boot:run
```

启动时 Spring Boot 会按 `compose.yaml` 自动拉起 PostgreSQL + pgvector 容器并配置好数据源，然后把题库向量化写进去，日志里看到 `题库索引完成，共 16 道题` 就可以用了。

**不用 Docker**：自己准备一个装了 pgvector 扩展的 PostgreSQL，然后：

```bash
export SPRING_DOCKER_COMPOSE_ENABLED=false
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/interview_agent
export SPRING_DATASOURCE_USERNAME=xxx
export SPRING_DATASOURCE_PASSWORD=xxx
```

数据库用户需要有创建扩展的权限，启动时会自动执行 `CREATE EXTENSION IF NOT EXISTS vector` 并建表。

### 换模型

对话模型换成通义千问：

```bash
export CHAT_API_KEY=sk-xxx          # 阿里云百炼
export CHAT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export CHAT_MODEL=qwen-plus
```

对话模型换成 Claude：

```bash
export AI_PROVIDER=anthropic
export ANTHROPIC_API_KEY=sk-ant-xxx
```

向量模型换成硅基流动或本地 Ollama 的 `bge-m3`（同样是 1024 维，不用改维度）：

```bash
# 硅基流动
export EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
export EMBEDDING_MODEL=BAAI/bge-m3
export EMBEDDING_API_KEY=sk-xxx

# 本地 Ollama（先执行 ollama pull bge-m3）
export EMBEDDING_BASE_URL=http://localhost:11434/v1
export EMBEDDING_MODEL=bge-m3
export EMBEDDING_API_KEY=ollama     # 随便填，不能为空
```

以上都走 OpenAI 兼容协议，实际请求地址是 `CHAT_BASE_URL` + `/chat/completions`、`EMBEDDING_BASE_URL` + `/embeddings`。

**换向量模型要注意两点：**

- 维度不同的模型（比如 1536 维）要同时设置 `EMBEDDING_DIMENSIONS`，并删掉旧的向量表 `vector_store`，启动时会按新维度重建。
- 不同模型生成的向量不能混用。换模型后重启一次即可，启动时会全量重建题库索引。

### 试一下

```bash
# 1a. 开始面试（简历直接传文本），记下返回的 sessionId
curl -s -X POST localhost:8080/api/interviews \
  -H 'Content-Type: application/json' \
  -d '{"position": "Java 后端开发", "yearsOfExperience": 5, "resume": "负责电商订单系统，日均订单 50 万"}'

# 1b. 或者上传简历 PDF 开始面试
curl -s -X POST localhost:8080/api/interviews \
  -F position='Java 后端开发' -F yearsOfExperience=5 -F resumeFile=@resume.pdf

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
| POST | `/api/interviews` | 开始面试，返回 `sessionId` 和开场白（含第一个问题）。JSON 请求体传简历文本；或用 `multipart/form-data` 上传 PDF（字段 `position`、`yearsOfExperience`，文件 `resumeFile`，最大 5MB） |
| POST | `/api/interviews/{id}/answers` | 提交回答，返回面试官的下一句 |
| POST | `/api/interviews/{id}/answers/stream` | 同上，以 SSE 流式返回 |
| POST | `/api/interviews/{id}/finish` | 结束面试，返回评估报告；重复调用直接返回已生成的报告 |
| GET | `/api/interviews/{id}` | 查询面试状态 |

错误响应统一为 ProblemDetail 格式：会话不存在返回 404，已结束或超出回答次数返回 409，参数校验失败或简历无法解析返回 400。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `AI_PROVIDER` | `openai` | 对话模型走哪种协议：`openai`（OpenAI 兼容接口，如 DeepSeek、通义千问）或 `anthropic` |
| `CHAT_API_KEY` | | 对话模型的 API Key |
| `CHAT_BASE_URL` | `https://api.deepseek.com` | 对话模型的接口地址 |
| `CHAT_MODEL` | `deepseek-chat` | 对话模型名，以各厂商文档为准 |
| `EMBEDDING_API_KEY` | | 向量模型的 API Key |
| `EMBEDDING_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 向量模型的接口地址 |
| `EMBEDDING_MODEL` | `text-embedding-v4` | 向量模型名 |
| `EMBEDDING_DIMENSIONS` | `1024` | 向量维度，必须和向量模型的输出一致 |
| `ANTHROPIC_API_KEY` | | `AI_PROVIDER=anthropic` 时使用 |
| `ANTHROPIC_MODEL` | `claude-opus-5` | `AI_PROVIDER=anthropic` 时使用 |

`application.yml` 的 `interview.*` 下还有：主问题数量、单场最多回答次数、记忆保留条数、每次检索返回的题目数（`rag.top-k`）、每次调用 embedding 接口最多提交的条数（`rag.embedding-batch-size`，通义千问限制为 10）。

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

## RAG 是怎么工作的

RAG（检索增强生成）分两个阶段：

```
索引（启动时，QuestionIndexer）
  question-bank.json ─▶ 每道题拼成"分类 + 题目 + 考察要点"文本
                     ─▶ embedding 接口转成 1024 维向量（每批最多 10 条）
                     ─▶ 连同 metadata（来源、分类、难度）写入 pgvector

检索（面试中，模型调用 searchQuestions 工具时，QuestionRetriever）
  模型写的查询，如"订单系统的分布式事务"
                     ─▶ 同一个 embedding 接口转成向量
                     ─▶ pgvector 按余弦相似度取最相近的 top-k，可按难度过滤
                     ─▶ 按 ID 回题库取完整题目，作为工具结果交给模型
```

几个设计选择：

- **检索做成工具，由模型决定查什么**（常被称为 Agentic RAG），而不是每轮对话前固定检索一次。模型可以结合简历和候选人刚才的回答来组织查询。
- **题目 JSON 是唯一数据源**，向量库只是索引。检索到 ID 后回题库取题，改题目不用担心两边不一致。
- **每次启动全量重建索引**，题库里删改的题目也能同步。题库很大时每次都重新向量化会慢，可以改成按内容哈希增量更新。
- **简历不做向量化**。简历只有一两页，直接整份放进提示词更简单也更完整；只有资料多到放不进上下文时才需要切分和检索。

## 代码导读

建议按下表顺序读，每一行对应一个 AI 应用开发的知识点：

| 知识点 | 看哪里 |
|---|---|
| ChatClient 与提示词模板 | `config/AiConfig`、`resources/prompts/*.st`、`InterviewService#interviewerPrompt` |
| 对话记忆 | `AiConfig#chatMemory`、`MessageChatMemoryAdvisor` |
| 工具调用 | `questionbank/QuestionBankTools` |
| 结构化输出 | `interview/InterviewReport`、`InterviewService#finish` |
| 流式输出 | `InterviewController#answerStream` |
| RAG：索引 | `questionbank/QuestionIndexer`、`config/MaxSizeBatchingStrategy` |
| RAG：检索与过滤 | `questionbank/QuestionRetriever` |
| 检索效果评估 | `src/test/.../RetrievalEvalTest`、`src/test/resources/eval/retrieval-cases.json` |
| 文档解析 | `resume/ResumeParser` |
| 提示词注入防护 | `prompts/interviewer-system.st` 里的 `<resume>` 标签 |
| 成本控制 | `StartInterviewRequest` 的输入长度限制、`interview.max-answers` |
| 不依赖真实模型的测试 | `src/test/.../StubChatModel`、`FakeEmbeddingModel`、`InterviewFlowTest` |

## 项目结构

```
src/main/java/com/interviewagent
├── InterviewAgentApplication.java
├── config
│   ├── AiConfig.java                # ChatClient、ChatMemory、分批策略等 Bean
│   ├── InterviewProperties.java     # interview.* 配置
│   └── MaxSizeBatchingStrategy.java # 限制每次 embedding 请求的条数
├── interview
│   ├── InterviewController.java     # REST 接口
│   ├── InterviewService.java        # 面试流程
│   ├── InterviewSession.java        # 会话状态
│   ├── InterviewSessionStore.java   # 会话存储（内存）
│   ├── InterviewReport.java         # 评估报告（结构化输出）
│   └── ...Request / Reply           # 请求和响应 DTO
├── questionbank
│   ├── Question.java
│   ├── QuestionBank.java            # 从 question-bank.json 加载题库
│   ├── QuestionIndexer.java         # 启动时把题库写入向量库
│   ├── QuestionRetriever.java       # 语义检索
│   └── QuestionBankTools.java       # 暴露给模型的检索工具
└── resume
    └── ResumeParser.java            # 从简历 PDF 提取文字
src/main/resources
├── application.yml
├── question-bank.json               # 题库，可自行扩充
└── prompts
    ├── interviewer-system.st        # 面试官系统提示词
    └── evaluator-system.st          # 评估官系统提示词
compose.yaml                         # 本地开发用的 PostgreSQL + pgvector
```

## 测试

```bash
./mvnw test
```

测试用 `StubChatModel` 代替对话模型，用 `FakeEmbeddingModel`（按字符计数生成向量）+ 内存向量库代替 embedding 接口和 PostgreSQL，不需要 API Key 和 Docker，也不产生费用。覆盖完整面试流程、对话记忆、流式输出、简历上传、参数校验、索引与检索、工具参数 Schema，以及模型厂商的装配。

### 检索效果评估

`RetrievalEvalTest` 用真实的 embedding 模型检查检索质量：`eval/retrieval-cases.json` 里每条用例是一个查询和预期应该检索到的题目，查询故意换了说法（例如"高并发抢购活动"对应"秒杀系统"），考的是语义理解。它会打印每条用例的检索结果，并计算 recall@1、recall@3，recall@3 低于 0.8 判为失败。

```bash
EMBEDDING_API_KEY=sk-xxx ./mvnw test -Dtest=RetrievalEvalTest
```

没设置 `EMBEDDING_API_KEY` 时自动跳过。它会调用 embedding 接口（16 道题 + 16 条查询，费用可以忽略），不需要数据库。换 embedding 模型、改索引文本格式、调 top-k 之后都跑一遍，用数字判断效果变好还是变差。题库扩充后记得同步补充用例。

## 已知限制

- 会话和对话记忆都存在内存里，重启就丢失。
- 没有鉴权。不要直接暴露到公网，否则任何人都能调用接口，花的是你的 API 额度。
- 评估报告靠提示词约束模型输出 JSON，而不是厂商的原生 JSON Schema 模式（DeepSeek 不支持后者）。模型偶尔可能输出不合法的 JSON，导致生成报告失败，重试即可。
- 只做了纯向量检索，还没有关键词混合检索和重排序（rerank）。
- 简历只支持能提取文字的 PDF，扫描件和图片需要 OCR，暂不支持。
- 切换到 Claude 时：Claude API 提供服务端 `fallbacks` 参数（请求被安全分类器拒绝时自动换模型重试），Spring AI 2.0.1 的 Anthropic 配置项里还没有这个参数，所以没有启用。面试场景一般不会触发拒绝。

## 下一步

- [x] RAG：题库向量化存进 pgvector，按简历和回答语义检索题目；支持上传简历 PDF
- [ ] RAG 进阶：关键词 + 向量混合检索、rerank 重排序；评估时检索题目的考察要点，让评分有据可依
- [ ] 评估集：收集一批面试记录并人工打分，检验评估官的打分和人工是否一致（LLM-as-judge 校准）
- [ ] 持久化：会话存数据库，对话记忆换成 `JdbcChatMemoryRepository` 或 Redis
- [ ] 可观测性：统计每场面试的 token 用量和成本，接入链路追踪
- [ ] 前端：聊天界面和报告展示页
- [ ] 语音面试：语音识别 + 语音合成
