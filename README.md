# RinBi

**v2.0** · 智能报表引擎：上传 Excel 模板，用自然语言描述需求，后端自动生成只读 SQL、回填模板并返回可下载的报表文件。

English module docs: [Frontend](RinBIFrontend/README.en.md) | [Backend](RinBIBackend/README.en.md)

这是一款基于 Spring Boot 的多数据源 LLM 查询分析 Agent。用户用自然语言提问，后端自动读取数据库 Schema、生成只读 SQL、经过安全校验后执行，并返回 Markdown 分析结果与结构化数据表格。**独创智能报表引擎**，告别繁琐的报表功能开发与数据库适配。用户编辑好excel模板上传并输入要求，后端自动生成excel报表文件，支持多轮对话调整。前端位于同级 `RinBIFrontend` 仓库。
安全定位：这是一个**只读分析助手**，不是通用 SQL 执行器。所有查询都必须经过 `SqlGuard`，非 `SELECT` / `WITH ... SELECT` 语句会被拒绝。

## 为什么开发这个agent
作者本人觉得AI最擅长的事情其实还是对已知的数据做计算和分析，我要做的就是规划设计并划定安全边界，然后让AI在这个范围内发挥它的特长。由于资源有限作者没有对所有类型的数据库逐一测试，只测试了三种通用开源（mysql,postgresql,h2）的数据库。分析结果的准确度主要看模型的能力，支持连接Ollama本地模型。

## 功能特性

- **自然语言问答**：基于 LangChain4j + OpenAI 兼容协议，可对接 DeepSeek、Qwen、OpenAI 等模型。
- **用户登录与数据隔离**：`PBKDF2-SHA256` 密码哈希、不透明 Bearer token；用户只能访问已授权的数据源和历史会话。
- **注册审批与管理员权限**：新用户可自助提交注册申请（`status=2`），管理员审批通过后才能登录；禁用账号会立即吊销其在线 token。只有 `ADMIN` 可以访问 `/api/admin/**`。
- **全局数据源 + 按用户授权**：连接配置统一保存在 `rinbidb.data_source`，用户访问权限保存在 `rinbidb.user_data_source` 中间表；支持 H2 / MySQL / PostgreSQL 等，密码使用 AES-GCM 加密后落库。管理员先配置数据源，再在授权弹框中勾选可访问用户，连接池按配置版本自动重建。
- **只读安全校验**：JSqlParser 解析，拒绝写操作、多语句、危险函数与系统表访问；执行层再次校验。
- **资源保护**：最大行数、结果字符数、查询超时、Schema 字符数、模型整理数据量均有上限。
- **会话记忆与历史**：会话上下文按用户 + `conversationId` 隔离；每轮问答持久化到 `chat_conversation` / `chat_message`，服务重启后可从历史恢复并继续追问。
- **结构化结果**：响应同时包含 `answer`（Markdown）与 `table`（列/行），前端可渲染表格、分页、SQL 追溯。
- **业务化输出**：查询结果的列名优先使用数据库表/字段注释；模型答案、思考过程、图表标题中的真实表名/字段名会被替换为业务名称，SSE 分片同样在服务端完成替换。
- **多图表可视化**：模型可在答案末尾给出多个受约束的 `chart` 代码块，后端逐个校验列名与数值类型后转换为 `charts`（最多 4 个）；前端用 ECharts 按 `table` 数据渲染柱状图、折线图、饼图或散点图，并以响应式网格并排展示，不执行模型输出的 HTML/JS。
- **统计规则（业务口径）**：按数据源维护可复用的计算公式与业务约束，拥有该数据源权限的登录用户都能查看，并在提问时勾选需要遵循的规则；规则会以 JSON 注入查询规划提示词，约束过滤、连接、时间窗口、去重与聚合口径。规则代码全局唯一，仅管理员可新增、修改、删除，单次最多注入 50 条。
- **智能报表（v2.0）**：上传 `.xlsx` / `.xls` 模板后，模板文件与生成的报表一样保存到服务器（元信息写入 `report_template`），并创建独立的「智能报表」会话。后端用 Apache POI 解析工作表、表头、合并单元格与 `{{占位符}}`，把模板结构、数据库 Schema、历史需求与上一轮取数计划一起交给模型；模型返回“SQL 别名 → 单元格/表格区域”的填充计划，后端再次校验工作表、坐标、列名与合并区域，仅通过 `QueryExecutor` 执行只读 SQL，再用 POI 回填并生成新的 Excel 文件。一个会话只允许导入一个模板，生成报表后可以继续在同一会话里用自然语言要求调整取数口径、数据源或统计规则，每轮都会输出新的报表；模板与报表文件均可由所属用户下载，会话累计输入达到上限时会提示新建会话并重新上传模板。
- **两大功能分离**：数据问答与智能报表使用各自的会话 ID、历史列表与消息，互不干扰；`chat_conversation.feature_type` 区分 `DATA_QA` / `REPORT`，历史列表可分别拉取并恢复对应会话。
- **演示数据**：首次启动为默认管理员自动创建 H2 内存演示库（客户、商品、订单），开箱即可体验。
- **前端**：Vue 3 + Vite + Element Plus + ECharts，顶部可切换「数据问答」与「智能报表」两个功能（会话与历史互相独立），支持登录/注册、历史会话恢复/删除、数据源选择、统计规则管理与规则选择、模板上传/下载与报表多轮调整、Markdown、结果表格、图表、警告与错误展示、响应式布局；管理员入口分为「用户管理」和「数据源管理」两个独立页面，分别提供用户分页审批、数据源分页维护与用户授权勾选。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 运行时 | Java 21（`maven.compiler.release=21`） |
| 后端 | Spring Boot 3.5.16、Spring MVC（Spring Framework 6.2.19）、Spring Retry 2.0.13、Spring AOP、MyBatis-Plus 3.5.17 |
| 模型 | LangChain4j 1.22.0（`langchain4j-open-ai`、`langchain4j-ollama`，OpenAI 兼容协议） |
| SQL 安全 | JSqlParser 4.4、JdbcTemplate、HikariCP 6.3.3 |
| 报表 | Apache POI 5.4.1（`poi` + `poi-ooxml`，Excel 模板解析与回填） |
| 数据库 | H2 2.3.232（演示）、MySQL Connector/J 9.7.0、PostgreSQL JDBC 42.7.11 |
| 日志 | Log4j2 2.24.3（`spring-boot-starter-log4j2`） |
| 测试 | JUnit Jupiter 5.12.2、Spring Boot Test、Mockito 5.17.0、MockMvc |
| 开发 | Spring Boot DevTools 3.5.16（热部署 / LiveReload） |
| 前端 | Vue 3.2、Vite 2.9、Element Plus 2.2、markdown-it、DOMPurify、ECharts 5.4 |

## 项目结构

```text
RinBIBackend/
├─ src/main/java/org/rin/
│  ├─ agent/                 # Agent 主流程、LLM 调用、会话记忆、DTO
│  ├─ admin/                 # 管理员用户审批、全局数据源配置与用户授权
│  ├─ auth/                  # 注册申请、登录、token、密码哈希、用户 Mapper
│  ├─ config/                # AgentProperties、LLM、拦截器、启动初始化
│  ├─ db/                    # 全局数据源注册、用户授权、Schema、SQL 校验、查询执行
│  ├─ history/               # 历史会话与消息持久化
│  ├─ report/                # 智能报表：模板存储与解析、计划校验、POI 回填、文件记录与下载
│  ├─ rule/                  # 统计规则 CRUD、数据源权限校验、对话规则提示词注入
│  ├─ security/              # 数据源密码 AES-GCM 加解密
│  ├─ exception/             # 业务异常
│  └─ web/                   # 统一异常响应
├─ src/main/resources/
│  ├─ application.yml        # 主配置
│  ├─ application-dev.yml    # 元数据库、端口、日志与 Agent 配置
│  ├─ db/rinbidb-schema.sql  # MySQL 8.0 幂等建表脚本
│  ├─ db/demo.sql            # H2 演示数据（UTF-8）
│  └─ prompts/               # 规划 / 结果整理系统提示词
├─ src/test/java/org/rin/    # 单元测试与接口流程测试
├─ docs/frontend-design.md   # 前端设计说明
└─ pom.xml
```

> 前端为同级独立仓库 `../RinBIFrontend`，使用说明与多图表渲染细节见其 `README.md`。

## 架构与请求链路

```text
浏览器 (Vue 3)
   │  Authorization: Bearer <token>
   │  POST /api/agent/chat
   ▼
AuthInterceptor ──► AuthService（token → AuthUser）
   │
   ▼
AgentController（userId 来自登录态）
   │
   ▼
AgentService
   ├─ SchemaService ──► DatabaseRegistry ──► 用户只读 HikariDataSource / JdbcTemplate
   ├─ StatRuleService ► 规则权限校验 + 规则 JSON 注入规划提示词
   ├─ LlmService ─────► LLM（规划 JSON → SQL）
   ├─ SqlGuard ───────► JSqlParser 只读校验
   ├─ QueryExecutor ──► 二次校验 + maxRows / timeout / 字符上限
   ├─ SchemaMetadata ─► 表/字段注释 → 业务列名
   ├─ DisplayNameSanitizer ► 答案、思考过程与 SSE 分片兜底替换
   ├─ ConversationMemory（按 user + conversationId 隔离，TTL）
   └─ HistoryService ─► rinbidb.chat_conversation / chat_message
```

智能报表在顶部独立入口中运行，复用同一套登录态、数据源授权、SQL 安全校验与历史记录，但使用独立的报表会话与历史列表：

```text
浏览器 (Vue 3)
   │ 1) multipart/form-data: file + conversationId
   │    POST /api/report/templates
   │ 2) application/json: conversationId + templateId + question + database + ruleIds
   │    POST /api/report/chat
   ▼
ReportController ─► ReportService
   ├─ ReportTemplateStore ──► 模板落盘（storage-dir/templates）+ report_template 元信息
   ├─ ExcelTemplateParser ──► POI 解析工作表、已用区域、表头、合并区域与 {{占位符}}
   ├─ SchemaService ────────► 当前用户可见的表/字段结构
   ├─ HistoryService ───────► 最近若干轮需求/结果摘要（报表会话）
   ├─ LlmService.planReport ► report-system.txt + 上一轮 plan_json → 调整后的 ReportPlan JSON
   ├─ ReportService 二次校验 ► 单轮/累计输入上限、工作表、1-based 坐标、列名、SQL alias、合并区域可写性
   ├─ QueryExecutor ────────► SqlGuard 只读校验 + 超时/行数/字符上限
   ├─ ExcelReportFiller ────► POI 回填、数据行扩展、样式与横向合并复制
   ├─ report_file + 本地目录 ► 文件元信息落库（含 template_id），文件写入 agent.report.storage-dir
   └─ HistoryService ───────► feature_type=REPORT，response JSON 含 report / template 信息，可恢复并继续调整
```


元数据（用户、数据源、授权关系、统计规则、历史）统一保存在本地 MySQL 8.0 的 `rinbidb` 库，由 MyBatis-Plus 访问；被查询的业务库则按全局数据源配置动态创建只读连接池。

## 快速开始

### 环境要求

- JDK 21（`pom.xml` 中 `java.version=21`；Spring Boot 3.5.16 最低要求 JDK 17）
- Maven 3.6.3+（推荐 3.9.x；`maven-compiler-plugin` 3.14.1 要求 Maven ≥ 3.6.3）
- MySQL 8.0（保存用户、数据源与历史会话；`createDatabaseIfNotExist=true` 会自动建库）
- Node.js 14.16+（前端构建使用 Vite 2，兼容该版本）
- 可访问的 LLM 服务；或本地 Ollama（可选）

### 1. 配置环境变量

项目默认使用 DeepSeek 的 OpenAI 兼容接口，元数据库默认为 `localhost:3306/rinbidb`。启动前设置环境变量：

```powershell
# Windows PowerShell
$env:LLM_API_KEY = "sk-你的密钥"
$env:RINBI_DB_URL = "jdbc:mysql://localhost:3306/rinbidb?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
$env:RINBI_DB_USERNAME = "root"
$env:RINBI_DB_PASSWORD = "你的密码"
```

```bash
# Linux / macOS
export LLM_API_KEY="sk-你的密钥"
export RINBI_DB_USERNAME="root"
export RINBI_DB_PASSWORD="你的密码"
```

可选环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LLM_API_KEY` | 配置文件内的开发密钥 | 模型 API Key；未配置时聊天接口会返回错误 |
| `RINBI_DB_URL` | `jdbc:mysql://localhost:3306/rinbidb?...` | 元数据库 JDBC URL |
| `RINBI_DB_USERNAME` | `root` | 元数据库账号 |
| `RINBI_DB_PASSWORD` | `root123` | 元数据库密码 |
| `RINBI_SECRET` | `rinbi-local-dev-secret` | 数据源密码 AES-GCM 加密密钥，生产必须覆盖 |
| `RINBI_TOKEN_TTL_MINUTES` | `720` | 登录 token 有效期（分钟） |
| `RINBI_BOOTSTRAP_ENABLED` | `true` | 是否初始化默认管理员与演示数据源 |
| `RINBI_ADMIN_USERNAME` | `admin` | 默认管理员用户名 |
| `RINBI_ADMIN_PASSWORD` | `admin123` | 默认管理员初始密码，部署后请立即修改 |
| `LLM_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容接口地址 |
| `LLM_MODEL_NAME` | `deepseek-flash` | 模型名称 |
| `LLM_REASONING_MODEL_NAME` | `deepseek-flash` | 结果整理阶段使用的模型；留空回退到 `LLM_MODEL_NAME` |
| `LLM_THINKING_TYPE` | `enabled` | 请求体 `thinking.type`（DeepSeek 支持 `enabled` / `disabled`）；留空不下发 |
| `LLM_REASONING_EFFORT` | 空 | 请求体 `reasoning_effort`（DeepSeek 支持 `low` / `high` / `max`）；留空不下发 |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | 本地 Ollama 地址 |
| `OLLAMA_MODEL_NAME` | `qwen2.5:7b` | 本地 Ollama 模型 |

### 2. 启动后端

```bash
mvn spring-boot:run
```

默认激活 `dev` profile，端口 `8080`。启动时会自动执行 `db/rinbidb-schema.sql` 建表，并在首次启动时创建设置中的管理员账号与 H2 演示数据源（默认 `admin / admin123`）。健康检查无需登录：

```bash
curl http://localhost:8080/api/agent/health
```

### 热部署（开发环境）

项目已引入 `spring-boot-devtools`，并在 `application-dev.yml` 中开启自动重启与 LiveReload。devtools 监听的是编译输出目录 `target/classes`，因此修改 Java 代码后需要先完成编译，应用才会重启：

```bash
# 终端 1：启动应用（保持运行）
mvn spring-boot:run

# 终端 2：修改代码后手动触发增量编译
mvn compile
```

如果使用 IDE，建议开启自动编译，保存文件即可触发重启：

- **IntelliJ IDEA**：`Settings > Build, Execution, Deployment > Compiler` 勾选 `Build project automatically`；再到 `Settings > Advanced Settings` 勾选 `Allow auto-make to start even if developed application is currently running`。
- **VS Code**：安装 Java 扩展后默认保存即编译，无需额外配置。

说明：

- 修改 `application*.yml`、`db/*.sql`、`prompts/*` 等资源文件时，IDE 会同步到 `target/classes`，devtools 会自动重启；命令行下执行 `mvn compile` 也会一并复制资源。
- `static/`、`public/`、`templates/` 下的静态资源变更只触发 LiveReload（浏览器需安装 LiveReload 插件，或使用支持该协议的开发工具），不会重启应用。
- devtools 仅在开发时生效，`spring-boot-maven-plugin` 打包时会自动排除，不影响生产环境。
- 如需临时关闭自动重启：`mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.enabled=false"`。

### 3. 启动前端

```bash
cd ../RinBIFrontend
npm ci
npm run dev
```

浏览器访问 <http://localhost:5173>。Vite 会把 `/api` 代理到 `http://localhost:8080`；如需修改，复制 `.env.example` 为 `.env.local` 并设置 `VITE_PROXY_TARGET`。

### 4. 体验问题

前端内置示例，也可以先登录拿到 token 再调用接口：

```bash
# 1. 登录，响应中的 token 用于后续请求
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\"}"

# 2. 带 token 提问
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d "{\"question\":\"2026 年各产品类别的销售额排名\"}"
```

首次请求会自动创建 `conversationId`；后续请求带上该 id 即可延续上下文。问答会同时写入历史表，可通过 `/api/history/conversations` 查询与恢复。

### 5. 中途停止生成

数据问答与智能报表都支持在生成过程中停止：前端点击“停止”按钮、关闭页面或 SSE 连接断开时，都会调用停止接口取消当前任务。

```bash
curl -X POST http://localhost:8080/api/agent/conversations/<conversationId>/stop \
  -H "Authorization: Bearer <token>"
```

- 行为：中断模型流式调用与正在执行的只读查询（`Statement#cancel()`），后续阶段（修复 SQL、整理结果、回填报表）不再发起模型调用；被取消的一轮不写入会话记忆与历史记录。
- 响应：接口幂等，正常情况下返回 `204`；报错类同步接口（如 `/api/report/chat`）被停止时返回 `409`，响应体 `code` 为 `STOPPED`。流式接口会先推送 `stopped` 事件再关闭连接，前端据此保留已生成的部分内容并标注“已停止”。
- 限制：LangChain4j 的流式接口没有暴露取消能力，模型服务端可能仍会把当前这次回复生成完；被取消后应用侧不再接收、累积或推送分片，也不会再发起新的模型调用。

## 配置说明

主配置位于 `src/main/resources/application.yml`，配置前缀为 `agent`。

### 模型

```yaml
agent:
  llm:
    provider: openai-compatible
    base-url: ${LLM_BASE_URL:https://api.deepseek.com}
    api-key: ${LLM_API_KEY:sk-placeholder}
    model-name: ${LLM_MODEL_NAME:deepseek-flash}
    reasoning-model-name: ${LLM_REASONING_MODEL_NAME:deepseek-flash}
    # 写入请求体的 thinking.type，开启/关闭思考模式；留空不下发
    thinking-type: ${LLM_THINKING_TYPE:enabled}
    # 写入请求体的 reasoning_effort；留空不下发
    reasoning-effort: ${LLM_REASONING_EFFORT:}
    temperature: 0.2
    max-tokens: 4000
    timeout-seconds: 90
    max-retries: 2
    log-requests: false
    log-responses: false
    # 只控制是否解析响应中的 reasoning_content，不会向请求体写入思考模式参数
    return-thinking: true
```

> 接入其他 OpenAI 兼容服务时，只需修改 `base-url` 与 `model-name`。请勿把真实密钥提交到仓库。
>
> 注意：`return-thinking` 仅让 LangChain4j 解析响应中的 `reasoning_content` 并通过 `onPartialThinking` 回调，
> **不会**在请求体中添加任何字段。要真正开启 DeepSeek 的思考模式，必须配置 `thinking-type`（下发的请求体为
> `"thinking": {"type": "enabled"}`）。非 DeepSeek 服务若不支持该参数，请把 `thinking-type` 留空，避免请求被拒绝。

### SQL 资源限制

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `agent.sql.max-result-chars` | `30000` | 单次结果字符上限，超过则不返回数据 |
| `agent.sql.max-rows` | `500` | 最多读取行数，超过标记 `truncated` |
| `agent.sql.query-timeout-seconds` | `30` | JDBC 查询超时 |
| `agent.sql.max-schema-chars` | `30000` | 发送给模型的 Schema 上限 |
| `agent.sql.schema-cache-seconds` | `300` | Schema 元数据缓存时间 |
| `agent.sql.retry-max-attempts` | `3` | 瞬时数据库异常重试次数 |
| `agent.sql.retry-backoff-millis` | `500` | 重试退避时间 |
| `agent.sql.format-row-limit` | `100` | 交给模型整理的样本行数 |
| `agent.sql.format-max-chars` | `18000` | 交给模型整理的样本字符数 |
| `agent.sql.expose-sql` | `false` | 是否在响应中返回真实 SQL；SQL 含真实表/字段名，默认关闭以隐藏库表结构 |

### 智能报表

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `agent.report.storage-dir` | `data/reports` | 报表与模板的保存根目录，相对路径基于应用启动目录；模板保存其下的 `templates/` 子目录，可用 `RINBI_REPORT_DIR` 覆盖 |
| `agent.report.max-upload-bytes` | `10485760` | 模板上传上限（10MB），需与 `spring.servlet.multipart.max-file-size` 保持一致 |
| `agent.report.max-template-rows` | `200` | 构造提示词时最多解析的模板行数 |
| `agent.report.max-template-columns` | `60` | 构造提示词时最多解析的模板列数，同时限制映射坐标 |
| `agent.report.max-fill-rows` | `5000` | 单个表格区域最多回填的数据行数 |
| `agent.report.max-sql-count` | `5` | 单次报表最多执行的 SQL 条数 |
| `agent.report.max-prompt-chars` | `30000` | 单轮报表提示词（模板结构 + 表结构 + 历史 + 本轮需求）的最大字符数 |
| `agent.report.max-conversation-chars` | `120000` | 同一报表会话累计可用的输入字符预算；超出后必须新建会话并重新上传模板 |
| `agent.report.history-exchanges` | `4` | 生成报表时最多带上的历史问答轮数，避免多轮调整时上下文无限增长 |
| `agent.report.retention-days` | `7` | 文件保留天数配置（预留，当前版本不会自动删除历史文件） |

- Spring Multipart 默认限制需同步调整：`spring.servlet.multipart.max-file-size: 10MB`、`max-request-size: 12MB`；超过限制由 `GlobalExceptionHandler` 返回可读的 400 错误。
- `ReportService` 不信任模型输出：SQL 逐条经过 `SqlGuard` 与 `QueryExecutor` 的只读校验；工作表必须存在、行列坐标必须在 1-based 有效范围内、列名必须能在结果集中找到；表格 `headerRow` 必须小于数据起始行 `startRow`，写入合并区域非左上角会被拒绝并提示正确地址。
- 上传接口缺少 `file` 或必填参数、文件超过限制、扩展名不合法时统一返回 `400`，前端直接展示后端错误信息；同一 `conversationId` 重复导入模板同样返回 `400`。
- 模板与报表文件都按 `user_id` 校验归属，下载时校验解析后的路径必须位于存储根目录内；模板记录写入 `report_template`（一个会话唯一），生成文件写入 `report_file`（`template_id` 记录来源模板）。
- 单轮提示词超过 `max-prompt-chars` 或会话累计输入超过 `max-conversation-chars` 时返回 `400 / INPUT_LIMIT`，前端提示“新建会话并重新上传模板”。


### 字段展示名与输出清洗

- `SchemaMetadata` 从 JDBC 元数据读取表/字段注释。查询结果列名按“表名 + 字段名 → 唯一字段名 → 原始列名”的顺序转换；展示名重复时自动追加 `_2` 等后缀，保证表格与图表列名唯一。
- 模型整理结果时只接收 `table` 中的业务列名，格式化提示不包含 SQL，从源头减少真实表名/字段名进入答案。
- `DisplayNameSanitizer` 作为兜底，对 `answer`、`reasoning` 与图表标题做不区分大小写的单词边界替换；优先匹配“表名.字段名”，再匹配无歧义字段名与表注释。没有注释或同名但注释冲突的字段保持原样，避免误替换；未指定数据源时合并全部已授权数据源的注释。
- SSE 的 `reasoning` / `token` 分片通过 `DisplayNameSanitizer.Streaming` 输出：末尾可能尚未写完的 `[A-Za-z0-9_.]` 标识符会暂存，遇到分隔符或流结束时再替换并下发，避免 `user_` + `id` 这类跨分片字段名漏出；空分片不会推送。
- 表达式或聚合别名（如 `COUNT(*) AS customer_count`）没有数据库注释，仍按别名展示；需要业务名称时可在 SQL 中使用带注释的真实字段，或由数据源侧约定别名。
- `agent.sql.expose-sql` 默认 `false`，响应中的 `sql` 为 `null`；只有显式设为 `true` 时才返回 SQL 原文（包含真实表/字段名），且仅应展示给有数据源授权的用户。

### 会话与跨域

```yaml
agent:
  memory:
    max-exchanges: 6      # 每个会话保留的最近问答轮数
    ttl-minutes: 60       # 会话过期时间
  web:
    allowed-origins:      # 前后端分离开发时的允许来源
      - http://localhost:5173
      - http://127.0.0.1:5173
```

生产环境请把 `allowed-origins` 收紧为实际前端域名。留空时后端会对 `/api/**` 放开来源，便于本地调试，不建议用于生产。

### 登录、密钥与启动初始化

```yaml
agent:
  auth:
    token-ttl-minutes: 720        # 登录 token 有效期
  security:
    secret: ${RINBI_SECRET:rinbi-local-dev-secret}  # 数据源密码 AES-GCM 密钥
  bootstrap:
    enabled: true                 # 首次启动初始化，生产建议关闭
    admin-username: admin
    admin-password: admin123
    admin-display-name: 管理员
    demo-datasource-enabled: true
```

- 登录成功后返回不透明 token，通过 `Authorization: Bearer <token>` 或 `X-Auth-Token` 发送；`/api/auth/login`、`/api/auth/register` 与 `/api/agent/health` 不需要登录，其余 `/api/**` 都会被 `AuthInterceptor` 拦截。
- 新账号通过 `POST /api/auth/register` 提交申请，密码要求 8-64 位、用户名要求 3-32 位字母/数字/下划线；账号初始 `status=2`（待审批），管理员审批为 `1` 后才能登录，拒绝或禁用为 `0`。管理员禁用账号时，该用户已签发的 token 会立即从内存会话中移除。
- 密码使用 `PBKDF2-SHA256` 哈希（格式 `pbkdf2-sha256$iterations$salt$hash`）。
- 数据源密码使用 AES-256-GCM 加密，格式 `enc:v1:<base64>`；更换 `agent.security.secret` 后需要重新录入数据源密码。
- token 当前保存在进程内存中并按 TTL 清理，**服务重启后需要重新登录**；历史会话不受影响。生产多实例部署建议替换为 Redis 等共享存储。
- 启动初始化是幂等的：账号已存在时跳过；全局演示数据源已存在时复用，管理员未授权时会补建授权关系。

### 流式线程池

SSE 流式对话（生成计划 + 执行 SQL + 模型流式输出）使用独立线程池，避免长时间占用 Tomcat 请求线程。

```yaml
agent:
  stream:
    core-pool-size: 8        # 核心线程数
    max-pool-size: 32        # 最大线程数（小于核心线程数时按核心线程数处理）
    queue-capacity: 100      # 任务队列容量；<=0 使用 SynchronousQueue，不排队直接扩容线程
    keep-alive-seconds: 60   # 非核心线程空闲存活时间
    thread-name-prefix: agent-stream-
    daemon: true
```

线程与队列都打满时采用 `AbortPolicy` 直接拒绝，接口返回 `503 SERVER_BUSY`，可据此调大线程数或队列容量。

### 元数据库、数据源与用户授权

元数据保存在 MySQL 的 `rinbidb` 库，表结构由 `db/rinbidb-schema.sql` 维护（启动时幂等执行）：

| 表 | 用途 |
| --- | --- |
| `sys_user` | 登录账号、PBKDF2 密码哈希、角色与状态（0 禁用/拒绝、1 正常、2 待审批） |
| `data_source` | 全局查询数据源连接配置；密码 AES-GCM 加密，管理员统一维护 |
| `user_data_source` | 用户与数据源的授权关系（`user_id` + `data_source_id`） |
| `chat_conversation` | 历史会话（标题、`feature_type` 功能类型、最近数据源、报表会话累计输入字符数、更新时间） |
| `chat_message` | 用户问题与助手响应 JSON |
| `stat_rule` | 按数据源维护的统计规则（公式、业务规则、版本与创建人） |
| `stat_rule_input_field` | 规则依赖的表/字段及说明，随规则级联删除 |
| `report_template` | 智能报表导入模板：文件名、服务器存储名、结构摘要、最近一次取数计划与归属；一个会话仅一条 |
| `report_file` | 智能报表生成文件的元信息与归属（含来源 `template_id`）；文件本体保存在 `agent.report.storage-dir` |

`data_source` 关键字段：

| 字段 | 说明 |
| --- | --- |
| `db_key` | 接口 `database` 参数值，全局唯一 |
| `db_type` / `driver_class_name` | 数据库类型与 JDBC 驱动 |
| `jdbc_url` / `username` / `password` | 连接信息，密码落库前加密 |
| `dialect` | 方言提示（`mysql` / `postgresql` / `h2` 等），仅用于后台展示 |
| `read_only` / `max_pool_size` / `connection_timeout_millis` | 连接池与只读设置 |
| `init_script` | 可选初始化脚本（classpath 相对路径），演示库使用 `db/demo.sql` |

- 方言不需要手工维护：schema 文本头部直接标注真实数据库产品（取自 JDBC 元数据，如 `H2 2.3.232`），提示词要求模型严格按照该产品支持的语法与函数生成 SQL，因此即使 `dialect` 字段填错，也不会让模型把 `DATE_FORMAT` 之类的专有函数套到 H2 上。
- 数据源按用户授权：`/api/agent/databases` 只返回当前登录用户在 `user_data_source` 中已授权且启用的数据源。
- 连接池以数据源主键 + `updated_at` 版本为键缓存，管理员修改或删除配置后会主动关闭旧连接池，下一次查询按新配置重建。
- 访问已存在但未授权的数据源 key 返回 `403`，不存在的 key 返回 `400`。
- `read_only` 是连接层的尽力而为设置，**真正安全边界仍是 `SqlGuard`**；生产库请使用独立只读账号。
- 管理员先通过 `/api/admin/datasources` 创建连接配置，再通过 `/api/admin/datasources/{id}/users` 勾选授权用户；密码字段只写不读，查询接口只返回 `passwordConfigured` 标记。
- 旧版 `user_data_source` 表若包含 `db_key` 连接字段，启动脚本会自动迁移为 `data_source` + 授权中间表，并按 `db_key` 复用重复配置。

## 统计规则（业务口径）

统计规则把某个数据源上的业务指标口径固化下来，供拥有该数据源权限的用户在提问时复用。规则主表为 `stat_rule`，规则依赖的表/字段保存在 `stat_rule_input_field`（随规则级联删除）；删除 `data_source` 时，其下规则也会由外键级联清理。

`stat_rule` 关键字段：

| 字段 | 说明 |
| --- | --- |
| `data_source_key` | 规则所属数据源 key；规则只能用于该数据源 |
| `rule_code` | 规则代码，**全局唯一**，不区分数据源 |
| `rule_name` / `rule_description` | 规则名称与适用场景说明 |
| `formula` / `business_rule` | 计算公式，以及过滤、时间窗口、去重、分组等口径约束 |
| `remark` | 补充备注 |
| `version_no` | 后端维护的版本号，每次保存更新 |
| `created_by` / `updated_by` | 创建人与最后修改人，保留审计信息；新增、修改、删除仅限管理员 |

- 拥有数据源访问权限的登录用户都可以通过 `/api/rules` 查看规则，并在对话中勾选使用；仅 `ADMIN` 可以新增、修改、删除规则，普通用户只读，`canEdit` 仅在当前用户为管理员时为 `true`。管理员只能维护自己可访问数据源下的规则，写操作同样要求对目标数据源有访问权限；管理员可维护该范围内的任意规则，不再要求是创建人。
- `rule_code` 必填且最长 50 字符，`rule_name` 最长 128，`rule_description` / `remark` 最长 512，`formula` 最长 1024，`business_rule` 最长 4000；每条规则最多维护 50 个输入字段，字段按 `source_table + field_name` 去重（忽略大小写），单条字段说明最长 512 字符。
- 对话请求可携带 `ruleIds`，服务端去重后单次最多 50 条。只有显式指定 `database` 时才允许使用规则；未指定数据源却传了 `ruleIds`、规则不属于该数据源、规则不存在或用户无权访问时，分别返回 `400` / `403`。
- 后端在 `AgentService#prepare` 中校验并序列化规则，作为 JSON 注入查询规划提示词；`rulePromptJson` 字段被 `@JsonIgnore`，客户端不能伪造。规则只约束 SQL 规划（过滤、连接、时间窗口、去重与聚合口径），生成的 SQL 仍会经过 `SqlGuard`，规则也不参与结果后处理和图表生成。
- 请求示例：

```json
{
  "question": "统计本月的有效成交额",
  "database": "demo",
  "ruleIds": [12, 18]
}
```

规划提示词中的规则对象字段为 `ruleCode`、`ruleName`、`ruleDescription`、`formula`、`businessRule`、`remark`、`versionNo` 与 `inputFields`（`sourceTable` / `fieldName` / `fieldDescription`）。未选择规则时不会拼接该段提示词，行为与未引入规则前一致。

## 智能报表（v2.0）

智能报表把“Excel 模板 → 自然语言需求 → 只读 SQL → Excel 文件”串成一条可持续调整的链路，适合周报、月报、对账单等固定版式报表。与数据问答完全分离：使用独立的会话 ID（`rep-...`）、独立的历史列表与消息，`chat_conversation.feature_type = REPORT`。

### 使用流程

1. 切换到顶部 **智能报表** 标签，点击 **上传模板** 选择 `.xlsx` / `.xls` 文件。前端校验扩展名与 10MB 上限后调用 `POST /api/report/templates`；后端解析模板、把文件保存到 `agent.report.storage-dir/templates`，写入 `report_template`，并创建（或复用）一个报表会话。
2. 一个 `conversationId` 只允许一个模板：重复导入返回 `400`。需要更换模板时，前端会提示“新建报表会话”，旧会话与模板仍保留在历史列表中，可随时恢复与下载。
3. 描述报表需求后调用 `POST /api/report/chat`，同时携带当前选中的数据源、勾选的统计规则与 `conversationId`（`templateId` 可省略，默认取会话绑定的模板）。
4. 后端解析已保存的模板：工作表、已用区域、表头文本、合并单元格、`{{占位符}}`、公式原文以及前 `max-template-rows` / `max-template-columns` 范围内的单元格文本，都会进入报表提示词；公式单元格会同时带上计算结果与 `（公式 =...）` 标注，便于模型识别“这一格是算出来的”。
5. 提示词还会带上最近 `history-exchanges` 轮的需求/结果摘要，以及 `report_template.plan_json` 中上一轮生效的完整填充计划，模型据此理解“在已有报表上继续修改”，并输出调整后的完整计划。
6. 模型返回严格 JSON 的 `ReportPlan`：`sqls`（唯一 alias + 只读 SQL）、`cells`（单个单元格）、`tables`（明细区域）、`summary` 与 `feasible`。
7. 后端重新校验并执行 SQL，按 alias 建立结果集，再转换为 POI 填充计划：单点取第一行；表格按 `columns` 顺序写值，模板数据行的样式、行高与横向合并会复制到每一行，并在 `startRow` 处插入空行。
8. 每轮都会生成新的 Excel 文件，写入 `agent.report.storage-dir`，元信息写入 `report_file`（含来源 `template_id`）；响应中的 `report` 携带 `fileId`、`fileName`、`downloadUrl` 等，`template` 携带模板信息，`promptChars` / `inputChars` / `inputLimit` 用于展示输入预算。
9. 生成成功后，本轮取数计划回写 `plan_json`，并把 `promptChars` 累加进 `chat_conversation.input_chars`；任一轮失败不会占用预算。

### 输入上限与多轮调整

- 单轮提示词超过 `agent.report.max-prompt-chars`，或会话累计超过 `agent.report.max-conversation-chars` 时，接口返回 `400 / INPUT_LIMIT`，消息中说明当前用量与上限。
- 前端收到 `INPUT_LIMIT` 后提示“当前会话已达模型输入上限，请新建会话并重新上传模板”，并在模板面板持续展示 `上下文占用 x / y 字符`。
- 建议的调整方式：直接描述差异（如“按月份重排并增加同比列”“只统计已完成订单”），模型会尽量沿用未要求修改的别名、坐标与列顺序，减少报表结构漂移。
- 数据源与统计规则可以逐轮更换；规则仍只约束 SQL 统计口径，不参与 POI 回填。

### 会话恢复

- 报表会话与普通对话共用 `chat_conversation` / `chat_message`，但通过 `feature_type` 区分；`GET /api/history/conversations?feature=REPORT` 只返回报表会话。
- 会话详情额外返回 `featureType`、`template`（`templateId` / `templateName` / `downloadUrl` / `sheetSummary`）、`inputChars` 与 `inputLimit`；前端据此恢复消息、模板卡片与输入预算展示。
- 删除报表会话时，会一并删除 `report_template` 记录与模板文件；已生成的 `report_file` 记录保留，仍可按所属用户下载。

### 样例模板

仓库提供两个样例模板：

- [`samples/智能报表上传模板样例.xlsx`](samples/智能报表上传模板样例.xlsx)：单工作表、单表格，包含合并标题、`{{占位符}}`、表头、可扩展数据行与一列模板公式（`客单价 = 销售金额 / 订单数量`），适合快速验证「上传模板 → 注入统计规则 → 生成报表 → 下载」以及公式列不被覆盖、逐行复制重算。
- [`samples/股票数据报表模板样例.xlsx`](samples/股票数据报表模板样例.xlsx)：3 个工作表、5 个表格，基于股票测试数据，覆盖单工作表多表格、表格扩展与下方内容联动下移。

详细结构、测试数据准备和推荐需求见 [`samples/README.md`](samples/README.md)。

### 模板约定

- 坐标统一使用 Excel 的 **1-based** 行列号；`startRow` / `startCol` 指向表格区域的第一条数据行与第一列。
- `{{字段名}}` 表示作者指定的填充点，必须生成 `cell` 映射；合并区域中的占位符使用合并区域左上角地址。
- `tables[].headerRow` 可选：填写后把 SQL 结果列名写入该表头行；`columns` 为空时按结果列顺序全部写入。
- 模型只允许写入模板已存在的工作表与已用区域；合并单元格只有左上角可写，写到其他位置会返回带正确地址的 `400`。
- 模板自带的公式（合计、占比等）会以 `（公式 =...）` 标注进提示词，模型不会为其生成映射；回填完成后由 POI 按新数据重算缓存值，并写入强制重算标记，保证 Excel 与不重算的查看器都显示正确结果。POI 不支持的函数、跨工作簿引用会退化为 Excel 打开时重算。
- 表格扩展会复制模板数据行的样式、行高、横向合并与公式：数据行里的公式（如「客单价 = 销售金额 / 订单数量」）逐行复制，相对引用随行号平移，重算后得到每行结果；若该列已由 SQL 数据写入，则以数据为准，不再套用公式。模板公式无法解析（跨工作簿、结构化引用等）时跳过复制，交给 Excel 打开时重算。
- 模板本身不会被执行宏；只解析单元格内容与结构，`.xls` 与 `.xlsx` 均可，输出扩展名与上传模板一致。

### 响应示例

```json
{
  "conversationId": "rep-1a2b3c4d5e6f",
  "status": "SUCCESS",
  "answer": "### 报表已生成 ...",
  "table": null,
  "charts": [],
  "template": {
    "templateId": 12,
    "conversationId": "rep-1a2b3c4d5e6f",
    "templateName": "月度销售报表.xlsx",
    "downloadUrl": "/api/report/templates/12",
    "fileSize": 15360,
    "sheetSummary": "销售汇总(12行×8列)"
  },
  "report": {
    "fileId": 128,
    "fileName": "月度销售报表_报表_20261124163000.xlsx",
    "downloadUrl": "/api/report/files/128",
    "fileSize": 20480,
    "rowCount": 36,
    "summary": "按客户汇总本月订单数量与销售金额"
  },
  "promptChars": 8642,
  "promptLimit": 30000,
  "inputChars": 8642,
  "inputLimit": 120000,
  "warnings": [],
  "timestamp": "2026-11-24T16:30:00+08:00"
}
```

- 该响应会作为一条助手消息写入 `chat_message.response_json`，因此刷新页面或恢复报表会话后，报表与模板下载卡片仍然可见。
- 未生成任何可用映射、SQL 未返回数据导致空表、结果超过 `max-fill-rows` / 结果字符上限时，会通过 `warnings` 提示；无法生成时直接返回 `400` 与可读原因。
- `report` 与 `template` 文件仅所属用户可下载；非所属用户、记录不存在或物理文件缺失统一返回 `404`，避免泄露文件是否存在。
- 在同一会话继续发送调整要求时，响应结构与上例一致，`inputChars` 会继续累加；`template` 始终指向该会话唯一模板。


## HTTP API

除注册、登录与健康检查外，所有接口都需要 `Authorization: Bearer <token>`；未登录返回 `401`，访问他人资源、非管理员访问 `/api/admin/**` 或执行规则写操作返回 `403`。

**认证**（`/api/auth`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/register` | 自助注册申请，账号状态为待审批，不返回 token |
| `POST` | `/login` | 用户名密码登录，返回 `token`、`expiresAt` 与用户信息 |
| `GET` | `/me` | 当前登录用户 |
| `POST` | `/logout` | 销毁当前 token，返回 `204` |

**问答**（`/api/agent`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/health` | 健康状态；登录后附带当前用户数据源 key 与会话数量 |
| `GET` | `/databases` | 当前用户的数据源列表（不含连接凭据） |
| `POST` | `/chat` | 提交问题（SSE 流式响应；可选 `ruleIds` 注入统计规则） |
| `DELETE` | `/conversations/{conversationId}` | 清空服务端会话记忆，返回 `204` |
| `POST` | `/conversations/{conversationId}/stop` | 停止该会话正在进行的这一轮（流式问答或报表生成），幂等：没有进行中的任务时同样返回 `204` |

**历史**（`/api/history`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/conversations?limit=50&feature=REPORT` | 当前用户历史会话列表，按更新时间倒序；`feature` 取 `DATA_QA` / `REPORT`，留空返回全部 |
| `GET` | `/conversations/{conversationId}` | 会话详情，含完整消息、助手结构化响应、`featureType`；报表会话额外返回 `template`、`inputChars` 与 `inputLimit` |
| `DELETE` | `/conversations/{conversationId}` | 删除历史会话及其消息，返回 `204`；报表会话会同时清理导入的模板记录与模板文件 |

**智能报表**（`/api/report`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/templates` | `multipart/form-data`：`file`（必填，`.xlsx` / `.xls`，≤10MB）、`conversationId`（可选）；模板落盘保存并创建报表会话，返回 `templateId`、`templateName`、`downloadUrl`、`sheetSummary` 等；同一 `conversationId` 重复导入返回 `400` |
| `GET` | `/templates` | 当前用户导入过的模板列表，便于重新下载 |
| `GET` | `/templates/{templateId}` | 下载模板；仅模板所属用户可访问，非本人或文件缺失返回 `404` |
| `POST` | `/chat` | JSON：`conversationId`（必填）、`templateId`（可选）、`question`、`database`、`ruleIds`；按本轮需求生成或调整报表，返回 `ChatResponse`（含 `report`、`template`、`promptChars`、`inputChars` 等）；累计输入超限返回 `400 / INPUT_LIMIT` |
| `POST` | `/generate` | 兼容旧前端的一次性接口：等价于先 `POST /templates` 再 `POST /chat`，模板同样会保存并创建报表会话 |
| `GET` | `/files/{fileId}` | 下载报表文件；仅文件所属用户可访问，非本人或文件缺失返回 `404` |

模板与报表下载接口同样需要 token，因此前端不会直接使用 `<a href>`，而是携带认证头获取 Blob 后再触发浏览器保存；`Content-Disposition` 使用 UTF-8 文件名。


**管理员**（`/api/admin`，仅 `ADMIN`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/users?page=1&size=10&keyword=&status=all` | 用户分页，待审批优先；支持关键字与状态筛选 |
| `POST` | `/users/{userId}/approve` | 审批通过或重新启用账号，返回更新后的用户 |
| `POST` | `/users/{userId}/disable` | 禁用账号；对待审批账号等价于拒绝申请；不能禁用当前管理员自己 |
| `GET` | `/datasources?page=1&size=10&keyword=&enabled=all` | 全局数据源分页，含授权用户数量 |
| `POST` | `/datasources` | 新增全局数据源 |
| `PUT` | `/datasources/{dataSourceId}` | 修改数据源；`password` 留空表示保留原密码 |
| `DELETE` | `/datasources/{dataSourceId}` | 删除数据源及全部授权关系，返回 `204` |
| `GET` | `/datasources/{dataSourceId}/users` | 授权弹框数据：全部用户 + 当前已授权用户 ID |
| `PUT` | `/datasources/{dataSourceId}/users` | 以 `{"userIds":[...]}` 覆盖该数据源的用户授权 |

管理员接口会在 `AdminService` 中再次校验 `ADMIN` 角色，不能仅靠隐藏前端入口绕过。禁用账号会调用 `AuthService.revokeUserSessions` 立即吊销该用户全部在线 token。用户与数据源列表统一返回 `{ items, total, page, size }` 分页结构，`size` 最大为 100。

**统计规则**（`/api/rules`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/?page=1&size=10&dataSourceKey=&keyword=` | 当前用户可访问的规则分页；`size` 最大 100，列表不返回输入字段明细 |
| `GET` | `/options?dataSourceKey=demo` | 对话页候选项，仅返回 `id`、`ruleCode`、`ruleName`、`ruleDescription` |
| `GET` | `/{ruleId}` | 规则详情，含 `inputFields`、`canEdit` 与审计信息 |
| `POST` | `/` | 新增规则（仅管理员）；请求体见 `StatRuleRequest`，创建人由登录态写入 |
| `PUT` | `/{ruleId}` | 覆盖修改规则及输入字段（仅管理员） |
| `DELETE` | `/{ruleId}` | 删除规则及输入字段（仅管理员），返回 `204` |

统计规则接口同样需要 Bearer token，读取按数据源授权过滤。新增、修改、删除在 `StatRuleService` 中强制校验 `ADMIN` 角色，普通用户只能查看和使用规则；`canEdit` 只用于前端按钮展示，不能替代服务端鉴权。

## 应用截图




