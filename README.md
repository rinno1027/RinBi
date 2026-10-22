# RinBi
这是一款基于 Spring Boot 的多数据源 LLM 查询 Agent。用户用自然语言提问，后端自动读取数据库 Schema、生成只读 SQL、经过安全校验后执行，并返回 Markdown 分析结果与结构化数据表格。前端位于同级 `RinBIFrontend` 仓库。
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
- **演示数据**：首次启动为默认管理员自动创建 H2 内存演示库（客户、商品、订单），开箱即可体验。
- **前端**：Vue 3 + Vite + Element Plus + ECharts，支持登录/注册、历史会话恢复/删除、数据源选择、Markdown、结果表格、图表、警告与错误展示、响应式布局；管理员控制台提供用户分页、数据源分页与用户授权勾选。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 运行时 | Java 21（`maven.compiler.release=21`） |
| 后端 | Spring Boot 3.5.16、Spring MVC（Spring Framework 6.2.19）、Spring Retry 2.0.13、Spring AOP、MyBatis-Plus 3.5.17 |
| 模型 | LangChain4j 1.22.0（`langchain4j-open-ai`、`langchain4j-ollama`，OpenAI 兼容协议） |
| SQL 安全 | JSqlParser 4.4、JdbcTemplate、HikariCP 6.3.3 |
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
   ├─ LlmService ─────► LLM（规划 JSON → SQL）
   ├─ SqlGuard ───────► JSqlParser 只读校验
   ├─ QueryExecutor ──► 二次校验 + maxRows / timeout / 字符上限
   ├─ SchemaMetadata ─► 表/字段注释 → 业务列名
   ├─ DisplayNameSanitizer ► 答案、思考过程与 SSE 分片兜底替换
   ├─ ConversationMemory（按 user + conversationId 隔离，TTL）
   └─ HistoryService ─► rinbidb.chat_conversation / chat_message
```

元数据（用户、数据源、授权关系、历史）统一保存在本地 MySQL 8.0 的 `rinbidb` 库，由 MyBatis-Plus 访问；被查询的业务库则按全局数据源配置动态创建只读连接池。

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
| `chat_conversation` | 历史会话（标题、最近数据源、更新时间） |
| `chat_message` | 用户问题与助手响应 JSON |

`data_source` 关键字段：

| 字段 | 说明 |
| --- | --- |
| `db_key` | 接口 `database` 参数值，全局唯一 |
| `db_type` / `driver_class_name` | 数据库类型与 JDBC 驱动 |
| `jdbc_url` / `username` / `password` | 连接信息，密码落库前加密 |
| `dialect` | 提示模型使用（`mysql` / `postgresql` / `h2` 等） |
| `read_only` / `max_pool_size` / `connection_timeout_millis` | 连接池与只读设置 |
| `init_script` | 可选初始化脚本（classpath 相对路径），演示库使用 `db/demo.sql` |

- 数据源按用户授权：`/api/agent/databases` 只返回当前登录用户在 `user_data_source` 中已授权且启用的数据源。
- 连接池以数据源主键 + `updated_at` 版本为键缓存，管理员修改或删除配置后会主动关闭旧连接池，下一次查询按新配置重建。
- 访问已存在但未授权的数据源 key 返回 `403`，不存在的 key 返回 `400`。
- `read_only` 是连接层的尽力而为设置，**真正安全边界仍是 `SqlGuard`**；生产库请使用独立只读账号。
- 管理员先通过 `/api/admin/datasources` 创建连接配置，再通过 `/api/admin/datasources/{id}/users` 勾选授权用户；密码字段只写不读，查询接口只返回 `passwordConfigured` 标记。
- 旧版 `user_data_source` 表若包含 `db_key` 连接字段，启动脚本会自动迁移为 `data_source` + 授权中间表，并按 `db_key` 复用重复配置。

## HTTP API

除注册、登录与健康检查外，所有接口都需要 `Authorization: Bearer <token>`；未登录返回 `401`，访问他人资源或非管理员访问 `/api/admin/**` 返回 `403`。

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
| `POST` | `/chat` | 提交问题（SSE 流式响应） |
| `DELETE` | `/conversations/{conversationId}` | 清空服务端会话记忆，返回 `204` |

**历史**（`/api/history`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/conversations?limit=50` | 当前用户历史会话列表，按更新时间倒序 |
| `GET` | `/conversations/{conversationId}` | 会话详情，含完整消息与助手结构化响应 |
| `DELETE` | `/conversations/{conversationId}` | 删除历史会话及其消息，返回 `204` |

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

