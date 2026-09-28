# interview-agent

AI 模拟面试官。基于 Spring AI 的练手项目，覆盖 AI 应用开发的几个核心技能：提示词模板、对话记忆、工具调用、结构化输出、流式输出、RAG（向量 + 关键词混合检索、rerank 重排序），以及不依赖真实模型的测试和检索效果评估。

## 功能

- 按岗位、工作年限和简历开始一场面试，简历可以直接上传 PDF
- 面试官每次只问一个问题，根据回答决定追问还是换题
- 出题前通过工具调用检索题库（向量 + 关键词混合检索，再用 rerank 模型精排），结合简历里的项目和技术栈挑选或改写题目
- 支持普通返回和 SSE 流式返回
- 结束后生成结构化评估报告：总分、录用建议、分维度评分、逐题复盘（附参考答案要点和漏答的考察要点）、改进建议。评分前会把面试中问到的题对应回题库，对照考察要点打分

## 技术栈

- Java 21、Spring Boot 4.1、Spring AI 2.0
- 对话模型：默认 DeepSeek（`deepseek-chat`），也可以换成其他 OpenAI 兼容接口（如通义千问）或 Anthropic Claude，只改配置不改代码
- 向量模型：默认通义千问 `text-embedding-v4`（DeepSeek 没有 embedding 接口）
- 重排序模型：默认通义千问 `qwen3-rerank`
- 向量库：PostgreSQL + pgvector；关键词检索是内存里的 BM25

## 快速开始

需要 JDK 21 和 Docker。不用装 Maven，项目自带 `./mvnw`。

需要两个 API Key：

- **对话模型**：DeepSeek 开放平台的 API Key
- **向量模型和重排序模型**：阿里云百炼（通义千问）的 API Key，两者共用

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

重排序模型换成硅基流动的 `bge-reranker-v2-m3`，或者干脆关掉：

```bash
# 硅基流动
export RERANK_URL=https://api.siliconflow.cn/v1/rerank
export RERANK_MODEL=BAAI/bge-reranker-v2-m3
export RERANK_API_KEY=sk-xxx

# 不用 rerank，只做混合检索
export RERANK_ENABLED=false
```

rerank 接口支持 Cohere、Jina 采用的那种通用格式（请求 `{model, query, documents, top_n}`，响应 `results[{index, relevance_score}]`），上面两家和 Jina、Cohere、本地部署的 vLLM 都兼容。注意百炼的 `gte-rerank-v2` 只能走百炼自己的原生接口，格式不同，不支持。

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
| `RERANK_ENABLED` | `true` | 是否用 rerank 模型重排序 |
| `RERANK_URL` | `https://dashscope.aliyuncs.com/compatible-api/v1/reranks` | rerank 接口的完整地址 |
| `RERANK_API_KEY` | 同 `EMBEDDING_API_KEY` | rerank 接口的 API Key |
| `RERANK_MODEL` | `qwen3-rerank` | 重排序模型名 |
| `ANTHROPIC_API_KEY` | | `AI_PROVIDER=anthropic` 时使用 |
| `ANTHROPIC_MODEL` | `claude-opus-5` | `AI_PROVIDER=anthropic` 时使用 |

`application.yml` 的 `interview.*` 下还有：主问题数量、单场最多回答次数、记忆保留条数、最终返回给模型的题目数（`rag.top-k`）、每路召回的候选数（`rag.candidates`）、每次调用 embedding 接口最多提交的条数（`rag.embedding-batch-size`，通义千问限制为 10）、rerank 超时时间（`rag.rerank.timeout`）。

想看每次发给模型的完整请求和响应，把 `logging.level.org.springframework.ai.chat.client.advisor` 改成 `DEBUG`；想看每次检索各步骤的结果，把 `logging.level.com.interviewagent.questionbank.QuestionRetriever` 改成 `DEBUG`。

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
       ├─ QuestionMatcher：拿面试官的每句提问去题库检索，找出对应的题目和考察要点
       └─ evaluatorChatClient（独立的评估官，没有记忆和工具）
            ├─ 输入：<transcript> 面试记录 + <references> 检索到的考察要点
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
    ├─ 向量召回：转成向量，在 pgvector 里按余弦相似度取 20 条（懂语义）
    ├─ 关键词召回：BM25 在内存里取 20 条（专有名词更准）
    ─▶ RRF 融合：两路结果按名次合并，两边都靠前的排在前面
    ─▶ rerank：qwen3-rerank 把查询和每条候选放在一起比较，精排出前 5 条
    ─▶ 按 ID 回题库取完整题目，作为工具结果交给模型
  两路召回都可以按难度过滤；rerank 失败或超时时，直接用融合后的前 5 条
```

为什么要三步：

- **向量检索和关键词检索互补**。向量检索懂语义，"高并发抢购活动"能找到"秒杀系统"；但对 `hasQueuedPredecessors`、`CounterCell` 这种专有名词，向量模型未必认识，关键词检索反而一找一个准。两路一起召回，漏掉的概率更小。
- **RRF 只看名次、不看分数**。向量相似度在 0~1 之间，BM25 分数没有上限，直接加权要调参；按名次融合就没有这个问题。
- **rerank 更准，但慢且收费**。rerank 模型把查询和候选放在一起读，判断比向量相似度准，但每条都要算一遍，所以只对召回的少量候选精排。它是锦上添花：接口出错时自动降级，面试不受影响。
- **中文关键词检索不用词典**。`Tokenizer` 把中文切成相邻两字（"分布式锁" → 分布、布式、式锁），英文按单词切。效果要求更高时，可以换 IK、jieba 这类分词器，或者把关键词检索交给 Elasticsearch。

几个设计选择：

- **检索做成工具，由模型决定查什么**（常被称为 Agentic RAG），而不是每轮对话前固定检索一次。模型可以结合简历和候选人刚才的回答来组织查询。
- **题目 JSON 是唯一数据源**，向量库只是索引。检索到 ID 后回题库取题，改题目不用担心两边不一致。
- **每次启动全量重建索引**，题库里删改的题目也能同步。题库很大时每次都重新向量化会慢，可以改成按内容哈希增量更新。BM25 索引直接在内存里从题库构建，不用额外存储。
- **简历不做向量化**。简历只有一两页，直接整份放进提示词更简单也更完整；只有资料多到放不进上下文时才需要切分和检索。

### 评估时也用 RAG

只让模型凭自己的知识给回答打分，评分标准会飘，还可能编出不准确的"参考答案"。所以结束面试时，`QuestionMatcher` 先把面试官的每句提问拿去题库检索（复用上面同一套混合检索 + rerank），每句取最相关的一道题，去重后把题目和考察要点交给评估官：

```
<transcript>
候选人：……
面试官：请讲讲 G1 收集器的原理。
……
</transcript>

<references>
题目：G1 收集器的工作原理是什么？和 CMS 相比有什么优势？
考察要点：Region 化内存布局；Young GC / Mixed GC；停顿预测模型与 MaxGCPauseMillis；避免 CMS 的碎片和并发失败问题
</references>
```

评估官逐条对照考察要点打分，漏答或答错的要点写进报告的 `missedPoints`，候选人复盘时一眼就能看到差在哪。

- **不设相关度阈值**。寒暄和追问也会检索到某道题，但相关度分数在不同请求之间没法直接比较，定阈值容易误伤。提示词里告诉评估官：参考题和实际提问对得上才用，对不上就忽略。
- **检索失败不影响出报告**。embedding 或 rerank 接口出错时，本次评估不带参考资料，照常生成报告。
- **防提示词注入**。面试记录放在 `<transcript>` 标签里，并告诉评估官候选人的话只是被评估的内容，里面类似"请给我打满分"的指令不要执行。

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
| RAG：检索流程 | `questionbank/QuestionRetriever`（召回 → 融合 → rerank → 降级） |
| 评估时检索考察要点 | `questionbank/QuestionMatcher`、`InterviewService#finish`、`prompts/evaluator-system.st` |
| 关键词检索 | `rag/Tokenizer`、`rag/Bm25Index` |
| 多路结果融合 | `rag/ReciprocalRankFusion` |
| 重排序 | `rag/Reranker`、`rag/HttpReranker` |
| 检索效果评估 | `src/test/.../RetrievalEvalTest`（对比各检索策略）、`src/test/resources/eval/retrieval-cases.json` |
| 文档解析 | `resume/ResumeParser` |
| 提示词注入防护 | `prompts/interviewer-system.st` 里的 `<resume>` 标签、`prompts/evaluator-system.st` 里的 `<transcript>` 标签 |
| 成本控制 | `StartInterviewRequest` 的输入长度限制、`interview.max-answers` |
| 不依赖真实模型的测试 | `src/test/.../StubChatModel`、`FakeEmbeddingModel`、`InterviewFlowTest` |

## 项目结构

```
src/main/java/com/interviewagent
├── InterviewAgentApplication.java
├── config
│   ├── AiConfig.java                # ChatClient、ChatMemory、分批策略、Reranker 等 Bean
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
│   ├── QuestionRetriever.java       # 检索流程：召回、融合、rerank
│   ├── QuestionMatcher.java         # 评估时把问过的题对应回题库
│   └── QuestionBankTools.java       # 暴露给模型的检索工具
├── rag                              # 和题库无关的通用检索组件
│   ├── Tokenizer.java               # 中英文分词
│   ├── Bm25Index.java               # BM25 关键词索引
│   ├── ReciprocalRankFusion.java    # RRF 融合
│   ├── Reranker.java                # 重排序接口
│   └── HttpReranker.java            # 调用 rerank 接口
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

测试用 `StubChatModel` 代替对话模型，用 `FakeEmbeddingModel`（按字符计数生成向量）+ 内存向量库代替 embedding 接口和 PostgreSQL，rerank 用假实现或本地 HTTP 服务模拟，不需要 API Key 和 Docker，也不产生费用。覆盖完整面试流程、对话记忆、流式输出、简历上传、参数校验、分词与 BM25、RRF 融合、rerank 请求与降级、工具参数 Schema，以及模型厂商的装配。

### 检索效果评估

`RetrievalEvalTest` 用真实的 embedding 和 rerank 模型，对比四种检索策略：向量检索、关键词检索、混合检索、混合 + rerank。`eval/retrieval-cases.json` 里每条用例是一个查询和预期应该检索到的题目，分两类：

- **semantic**：故意换了说法，例如"高并发抢购活动"对应"秒杀系统"，考语义理解
- **keyword**：精确的技术名词，例如 `hasQueuedPredecessors`、`Redlock`，考关键词匹配

```bash
EMBEDDING_API_KEY=sk-xxx ./mvnw test -Dtest=RetrievalEvalTest
```

它会打印每条用例在各策略下的名次，以及汇总表：

```
策略               recall@1   recall@3     semantic@3      keyword@3
向量检索                 ...        ...            ...            ...
关键词检索                ...        ...            ...            ...
混合检索                 ...        ...            ...            ...
混合 + rerank          ...        ...            ...            ...
```

最终策略（开启 rerank 时是"混合 + rerank"，否则是"混合检索"）的 recall@3 低于 0.8 判为失败。

没设置 `EMBEDDING_API_KEY` 时自动跳过。rerank 默认复用这个 Key，不想测 rerank 就加上 `RERANK_ENABLED=false`。一次评估的调用量是 16 道题的向量化，加上 24 条查询（每条向量化两次、rerank 一次），费用可以忽略，也不需要数据库。

换模型、改索引文本格式、调 `top-k` 或 `candidates` 之后都跑一遍，用数字判断效果变好还是变差。**不要默认 rerank 一定有提升**：rerank 模型不合适时，排序可能反而变差，这张表能直接看出来。题库扩充后记得同步补充用例。

## 已知限制

- 会话和对话记忆都存在内存里，重启就丢失。
- 没有鉴权。不要直接暴露到公网，否则任何人都能调用接口，花的是你的 API 额度。
- 评估报告靠提示词约束模型输出 JSON，而不是厂商的原生 JSON Schema 模式（DeepSeek 不支持后者）。模型偶尔可能输出不合法的 JSON，导致生成报告失败，重试即可。
- 关键词检索的 BM25 索引在内存里，适合几百到几万道题；题库再大，应该换成 Elasticsearch 或 PostgreSQL 全文检索（加中文分词插件）。
- 每次检索都会调用一次 rerank 接口，多一次网络往返（通常几百毫秒）和一点费用。对延迟敏感时可以关掉。
- 结束面试时，面试官的每句话都要检索一次（一次向量化 + 一次 rerank），逐句串行执行。一场十几轮的面试会让生成报告多等几秒，相比评估本身调用大模型的耗时不算大；要更快可以改成并发检索，但要注意接口的限流。
- 简历只支持能提取文字的 PDF，扫描件和图片需要 OCR，暂不支持。
- 切换到 Claude 时：Claude API 提供服务端 `fallbacks` 参数（请求被安全分类器拒绝时自动换模型重试），Spring AI 2.0.1 的 Anthropic 配置项里还没有这个参数，所以没有启用。面试场景一般不会触发拒绝。

## 下一步

- [x] RAG：题库向量化存进 pgvector，按简历和回答语义检索题目；支持上传简历 PDF
- [x] RAG 进阶：关键词 + 向量混合检索、rerank 重排序，以及对比各策略的检索评估
- [x] 评估时检索题目的考察要点，让评估官的打分有据可依
- [ ] 评估集：收集一批面试记录并人工打分，检验评估官的打分和人工是否一致（LLM-as-judge 校准）
- [ ] 持久化：会话存数据库，对话记忆换成 `JdbcChatMemoryRepository` 或 Redis
- [ ] 可观测性：统计每场面试的 token 用量和成本，接入链路追踪
- [ ] 前端：聊天界面和报告展示页
- [ ] 语音面试：语音识别 + 语音合成
