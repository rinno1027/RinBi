# RinBI Backend

**English** | [Project README (Chinese)](../README.md)

RinBI Backend is the Spring Boot service behind the RinBI natural-language data assistant. It accepts a user question, inspects authorized database schemas, asks an OpenAI-compatible model to produce a query plan, validates the generated SQL as read-only, executes it with strict resource limits, and returns a Markdown answer plus a structured table and optional chart specifications.

RinBI is a **read-only analytics assistant**, not a general SQL execution service. Every generated statement must pass `SqlGuard`; DML, DDL, multiple statements, dangerous functions, and file-export constructs are rejected.

The frontend is documented in [RinBIFrontend/README.en.md](../RinBIFrontend/README.en.md).

## Table of contents

- [Features](#features)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Environment variables](#environment-variables)
- [Configuration](#configuration)
- [Metadata database](#metadata-database)
- [Authentication and authorization](#authentication-and-authorization)
- [Data sources and user access](#data-sources-and-user-access)
- [Statistical rules](#statistical-rules)
- [HTTP API](#http-api)
- [SSE protocol](#sse-protocol)
- [Charts and display names](#charts-and-display-names)
- [Build and test](#build-and-test)
- [Project structure](#project-structure)
- [Production checklist](#production-checklist)
- [Related documentation](#related-documentation)

## Features

- Natural-language questions over multiple databases using LangChain4j and the OpenAI-compatible protocol.
- Support for DeepSeek, OpenAI, Qwen, and compatible endpoints, plus optional local Ollama streaming models.
- Self-service registration requests with administrator approval and enable/disable controls.
- Opaque bearer tokens with configurable TTL and immediate session revocation when an account is disabled.
- `PBKDF2-SHA256` password hashing and AES-256-GCM encryption for stored data-source passwords.
- Globally managed data sources with per-user authorization.
- Dynamic read-only Hikari connection pools rebuilt when a data-source configuration changes.
- `JSqlParser` read-only validation followed by a second execution-layer safety check.
- Limits on rows, result characters, SQL length, schema size, query timeout, and model input size.
- Per-user and per-conversation memory with TTL, plus persistent conversation history.
- Structured `{ columns, rows }` results alongside the Markdown answer.
- Business display names derived from table/column comments, with stream-safe sanitization.
- Up to four validated chart specifications per answer; chart data is reused from the result table.
- Reusable statistical rules that constrain query planning for a specific data source.
- Idempotent bootstrap of a default administrator and an H2 demo database.
- Server-Sent Events for reasoning and answer streaming.

## Architecture

```text
Browser
  |  Authorization: Bearer <token>
  |  POST /api/agent/chat
  v
AuthInterceptor -> AuthService (token -> AuthUser)
  |
  v
AgentController (user ID comes from the authenticated session)
  |
  v
AgentService
  +- SchemaService -> DatabaseRegistry -> per-user read-only HikariDataSource/JdbcTemplate
  +- StatRuleService -> validates access and injects selected rules into the planning prompt
  +- LlmService -> OpenAI-compatible model -> query plan JSON -> SQL
  +- SqlGuard -> JSqlParser read-only validation
  +- QueryExecutor -> second validation + row/character/timeout limits
  +- SchemaMetadata -> table/column comments -> business column names
  +- DisplayNameSanitizer -> final and streaming replacement of physical identifiers
  +- ConversationMemory -> user + conversation isolation with TTL
  +- HistoryService -> chat_conversation / chat_message
```

User accounts, data sources, grants, rules, and chat history are stored in the local MySQL `rinbidb` database and accessed through MyBatis-Plus. Query targets are separate business databases with dynamically created pools.

## Tech stack

| Layer | Technology |
| --- | --- |
| Runtime | Java 21 (`maven.compiler.release=21`) |
| Framework | Spring Boot 3.5.16, Spring MVC, Spring AOP |
| Persistence | MyBatis-Plus 3.5.17, HikariCP |
| Model integration | LangChain4j 1.22.0 (`langchain4j-open-ai`, `langchain4j-ollama`) |
| SQL parsing | JSqlParser 4.4 |
| Metadata database | MySQL 8.0 |
| Query drivers | H2 2.3.232, MySQL Connector/J 9.7.0, PostgreSQL JDBC 42.7.11 |
| Logging | Log4j2 2.24.3 |
| Retry | Spring Retry 2.0.13 |
| Testing | JUnit Jupiter 5.12.2, Spring Boot Test, Mockito, MockMvc |
| Development | Spring Boot DevTools |

## Requirements

- JDK 21. Spring Boot 3.5.16 requires at least JDK 17, but this project targets Java 21.
- Maven 3.6.3 or newer; Maven 3.9.x is recommended.
- MySQL 8.0 for the metadata database.
- Network access to an OpenAI-compatible LLM endpoint, or a local Ollama instance.
- Node.js 14.16 or newer only if you also run the frontend from this repository.

## Quick start

### 1. Prepare MySQL

Create or allow the application to create a database named `rinbidb`. The default JDBC URL includes `createDatabaseIfNotExist=true`, so a user with database-creation privileges can start with an empty server.

The schema script at `src/main/resources/db/rinbidb-schema.sql` is idempotent and runs on every startup through `spring.sql.init.mode=always`.

### 2. Configure environment variables

At minimum, provide an LLM API key and the metadata database credentials.

Windows PowerShell:

```powershell
$env:LLM_API_KEY = "replace-with-your-key"
$env:RINBI_DB_USERNAME = "root"
$env:RINBI_DB_PASSWORD = "replace-with-your-password"
```

Linux or macOS:

```bash
export LLM_API_KEY="replace-with-your-key"
export RINBI_DB_USERNAME="root"
export RINBI_DB_PASSWORD="replace-with-your-password"
```

Production deployments must also set a strong `RINBI_SECRET`, change the bootstrap administrator password, and disable or tightly control bootstrap initialization. Never commit real credentials.

### 3. Start the application

```bash
mvn spring-boot:run
```

The default profile is `dev`, the HTTP port is `8080`, and the context path is `/`. The bootstrap process creates a default administrator and, when enabled, an H2 in-memory demo data source with sample customer, product, and order data.

The default development administrator is `admin / admin123`. Change it through `RINBI_ADMIN_USERNAME` and `RINBI_ADMIN_PASSWORD` before exposing the service, and rotate it immediately after first login in any shared environment.

### 4. Check health

```bash
curl http://localhost:8080/api/agent/health
```

Health is public. When called with a valid token, it also includes the current user's data-source keys and the number of in-memory conversations.

### 5. Start the frontend

```bash
cd ../RinBIFrontend
npm ci
npm run dev
```

Open <http://localhost:5173>. Vite proxies `/api` to `http://localhost:8080` by default.

### 6. Try the API

```bash
# Sign in and copy the token from the response
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# Ask a question with SSE enabled
curl -N -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"question":"Show monthly revenue by product category for 2026"}'
```

The first chat request has no `conversationId`; the backend creates one and returns it. Reuse it in later requests to continue the conversation. Each completed exchange is also written to the history tables.

### Development hot reload

The project includes `spring-boot-devtools`, and the `dev` profile enables automatic restart and LiveReload. DevTools watches the compiled output in `target/classes`, so Java changes must be compiled before the application restarts:

```bash
# Terminal 1
mvn spring-boot:run

# Terminal 2, after editing Java sources
mvn compile
```

In an IDE, enable automatic compilation on save. Changes to `application*.yml`, `db/*.sql`, and `prompts/*` are copied to `target/classes` by the IDE or by `mvn compile` and trigger a restart. Static-resource changes under `static/`, `public/`, or `templates/` trigger LiveReload only.

DevTools is excluded from the executable package and does not affect production.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `LLM_API_KEY` | none; set explicitly | Model API key. The development YAML may contain a fallback value; do not rely on it in production. |
| `LLM_BASE_URL` | `https://api.deepseek.com` | OpenAI-compatible API base URL. |
| `LLM_MODEL_NAME` | `deepseek-flash` | Planning and formatting model name. |
| `LLM_REASONING_MODEL_NAME` | `deepseek-flash` | Model used for the answer-formatting phase; empty falls back to `LLM_MODEL_NAME`. |
| `LLM_THINKING_TYPE` | `enabled` | Value written to the request field `thinking.type`. DeepSeek accepts `enabled` or `disabled`; leave empty for providers that reject the field. |
| `LLM_REASONING_EFFORT` | empty | Value written to `reasoning_effort`; valid values depend on the provider. Empty means the field is not sent. |
| `OLLAMA_BASE_URL` | empty | Ollama base URL; used only when `agent.llm.ollama.enabled=true`. |
| `OLLAMA_MODEL_NAME` | empty | Ollama model name; used only when Ollama is enabled. |
| `RINBI_DB_URL` | `jdbc:mysql://localhost:3306/rinbidb?...` | Metadata MySQL JDBC URL. |
| `RINBI_DB_USERNAME` | `root` | Metadata database username. |
| `RINBI_DB_PASSWORD` | `root123` | Metadata database password. |
| `RINBI_SECRET` | `rinbi-local-dev-secret` | AES-256-GCM secret for stored data-source passwords. Must be overridden in production. |
| `RINBI_TOKEN_TTL_MINUTES` | `720` | Login-token lifetime in minutes. |
| `RINBI_BOOTSTRAP_ENABLED` | `true` | Enables idempotent creation of the default administrator and demo data source. |
| `RINBI_ADMIN_USERNAME` | `admin` | Bootstrap administrator username. |
| `RINBI_ADMIN_PASSWORD` | `admin123` | Bootstrap administrator password. Change before deployment. |

> **Secret warning:** the checked-in development configuration may contain a fallback LLM key. Treat any committed credential as compromised, remove it from the repository, and always provide your own value through `LLM_API_KEY`.

The active profile is set in `application.yml` to `dev`. To use another profile, provide an `application-<profile>.yml` file and set `spring.profiles.active` accordingly. The HTTP port can be changed with `--server.port=<port>` or a profile-specific `server.port`.

## Configuration

Configuration is bound under `agent.*` by `AgentProperties`.

### Model

```yaml
agent:
  llm:
    provider: openai-compatible
    base-url: ${LLM_BASE_URL:https://api.deepseek.com}
    api-key: ${LLM_API_KEY:replace-me}
    model-name: ${LLM_MODEL_NAME:deepseek-flash}
    reasoning-model-name: ${LLM_REASONING_MODEL_NAME:deepseek-flash}
    thinking-type: ${LLM_THINKING_TYPE:enabled}
    reasoning-effort: ${LLM_REASONING_EFFORT:}
    temperature: 0.2
    max-tokens: 8000
    timeout-seconds: 300
    max-retries: 2
    log-requests: true
    log-responses: false
    return-thinking: true
    ollama:
      enabled: false
      base-url: ${OLLAMA_BASE_URL:}
      model-name: ${OLLAMA_MODEL_NAME:}
```

Notes:

- `return-thinking` controls whether LangChain4j parses `reasoning_content` from the response. It does not add a thinking field to the request.
- `thinking-type` writes `thinking: { type: <value> }` into the request body. It is a DeepSeek-specific option; leave it empty for incompatible endpoints.
- `reasoning-effort` is sent only when non-empty. Supported values depend on the model.
- Streaming models do not expose a built-in retry setting. Retries are handled by the service layer through Spring Retry.
- Never enable request logging in production if prompts or metadata are sensitive.

### SQL safety and resource limits

`SqlGuard` permits one `SELECT` or `WITH ... SELECT` statement and rejects DML, DDL, multiple statements, `SELECT INTO`, `FOR UPDATE`, file exports, dangerous functions, and access to protected system tables. `QueryExecutor` validates again before execution.

`SqlGuard` is the security boundary. The `read_only` connection flag is best-effort and does not replace a dedicated read-only database account.

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.sql.max-result-chars` | `30000` | Maximum result characters; larger results are not returned as data. |
| `agent.sql.max-rows` | `500` | Maximum rows read; additional rows mark the result as truncated. |
| `agent.sql.query-timeout-seconds` | `30` | JDBC query timeout. |
| `agent.sql.max-schema-chars` | `30000` | Maximum schema text sent to the model. |
| `agent.sql.max-sql-length` | `20000` | Maximum accepted SQL length. |
| `agent.sql.schema-cache-seconds` | `300` | Schema metadata cache lifetime. |
| `agent.sql.retry-max-attempts` | `3` | Retries for transient database failures. |
| `agent.sql.retry-backoff-millis` | `500` | Backoff between retries. |
| `agent.sql.format-row-limit` | `100` | Sample rows sent to the formatting model. |
| `agent.sql.format-max-chars` | `18000` | Sample characters sent to the formatting model. |
| `agent.sql.expose-sql` | `false` (property default) | Returns the executed SQL in the response. The development profile sets it to `true` for debugging; set it to `false` in production to avoid exposing physical table and column names. |

### Conversation memory and CORS

```yaml
agent:
  memory:
    max-exchanges: 6
    ttl-minutes: 60
    max-stored-answer-chars: 4000
  web:
    allowed-origins:
      - http://localhost:5173
      - http://127.0.0.1:5173
```

Memory is keyed by user ID and conversation ID. It preserves the most recent exchanges for follow-up questions and expires after the configured TTL. Full conversations are also persisted in MySQL and can be restored after a restart.

When `allowed-origins` is empty, the backend allows all origins for `/api/**`. That is convenient for local debugging but should not be used in production. The CORS configuration allows `GET`, `POST`, `PUT`, `DELETE`, and `OPTIONS`, with all headers, including `Authorization`.

### Authentication, encryption, and bootstrap

```yaml
agent:
  auth:
    token-ttl-minutes: 720
  security:
    secret: ${RINBI_SECRET:rinbi-local-dev-secret}
  bootstrap:
    enabled: true
    admin-username: admin
    admin-password: admin123
    admin-display-name: Administrator
    demo-datasource-enabled: true
```

- Passwords use `PBKDF2-SHA256` with a per-user salt. The stored format is `pbkdf2-sha256$iterations$salt$hash`.
- Data-source passwords use AES-256-GCM and are stored as `enc:v1:<base64>`. Changing `agent.security.secret` makes existing encrypted passwords unreadable; re-enter them after rotation.
- Opaque login tokens are held in process memory and cleaned up by TTL. A restart requires users to sign in again, but persisted chat history remains available. Multi-instance deployments should externalize sessions to a shared store such as Redis.
- Bootstrap is idempotent. If the administrator already exists it is reused. If the demo data source exists it is reused, and a missing administrator grant is added.
- Bootstrap is convenient for a demo, but production should set a strong administrator password and consider `RINBI_BOOTSTRAP_ENABLED=false` after initial provisioning.

### Streaming thread pool

SSE requests use a dedicated executor so long model and database work does not occupy Tomcat request threads.

```yaml
agent:
  stream:
    core-pool-size: 8
    max-pool-size: 32
    queue-capacity: 100
    keep-alive-seconds: 60
    thread-name-prefix: agent-stream-
    daemon: true
```

A queue capacity of zero or less uses a `SynchronousQueue` and expands directly toward the maximum thread count. The executor uses `AbortPolicy`; when both threads and queue are full, the request is rejected and the API returns `503 SERVER_BUSY`.

The backend also configures a 180-second Tomcat connection timeout, a 300-second request timeout, and an SSE emitter timeout of 600 seconds. A complete chat request therefore has room for planning, SQL execution, and streamed formatting.

## Metadata database

The metadata schema is defined in `src/main/resources/db/rinbidb-schema.sql` and is safe to run repeatedly.

| Table | Purpose |
| --- | --- |
| `sys_user` | Account, PBKDF2 hash, role, and status (`0` disabled/rejected, `1` active, `2` pending approval). |
| `data_source` | Global query data-source configuration; the password is encrypted at rest. |
| `user_data_source` | Grant relationship between users and data sources. |
| `chat_conversation` | Conversation title, most recent data source, and timestamps. |
| `chat_message` | User question or assistant response JSON. |
| `stat_rule` | Reusable statistical rule for one data source. |
| `stat_rule_input_field` | Tables and fields used by a rule, deleted with the rule. |

Important `data_source` fields:

| Field | Meaning |
| --- | --- |
| `db_key` | Globally unique key used by the chat `database` parameter. |
| `db_type` | Database type such as `mysql`, `postgresql`, `h2`, or `custom`. |
| `host`, `port`, `database_name` | Connection parts used when `jdbc_url` is absent. |
| `jdbc_url`, `jdbc_params` | Full JDBC URL and optional parameters. A non-empty URL takes precedence over assembled parts. |
| `driver_class_name` | Optional driver override; otherwise inferred from the type. |
| `username`, `password` | Credentials; the password is encrypted before storage. |
| `dialect` | Dialect hint passed to the model, for example `mysql`, `postgresql`, or `h2`. |
| `read_only`, `max_pool_size`, `connection_timeout_millis` | Pool and read-only settings. |
| `init_script` | Optional classpath-relative initialization script, such as `db/demo.sql`. |
| `enabled` | Whether the source is available for authorization and querying. |

The startup script also migrates a legacy `user_data_source` table that stored connection columns directly. It moves connection data into `data_source` and preserves user grants. Review the script before running it against a database with custom changes.

## Authentication and authorization

- `POST /api/auth/register` creates a pending account (`status=2`) and does not issue a token. Usernames must be 3-32 characters using letters, digits, or underscores. Passwords must be 8-64 characters. Display names are optional and limited to 64 characters.
- `POST /api/auth/login` accepts a username and password and returns an opaque token, `expiresAt`, and user information. Pending, disabled, or invalid accounts are rejected.
- Tokens can be sent as `Authorization: Bearer <token>` or `X-Auth-Token: <token>`.
- `AuthInterceptor` protects `/api/**` except `/api/auth/login`, `/api/auth/register`, and `/api/agent/health`. It returns a JSON `401 UNAUTHORIZED` response when authentication fails.
- `GET /api/auth/me` returns the current user. `POST /api/auth/logout` destroys the current token.
- Registration approval and account disabling are administrator-only. Disabling an account calls `AuthService.revokeUserSessions` and invalidates all of that user's current tokens.
- `/api/admin/**` is checked in `AdminService`, not only in the UI. A non-administrator receives `403`, even if the request is sent directly.
- Rule reads are filtered by data-source access. Rule writes require the `ADMIN` role and access to the target data source.

Authorization failures use `401` for a missing or invalid session and `403` for insufficient role or resource access.

## Data sources and user access

Administrators configure data sources globally and grant each user access individually.

- `GET /api/agent/databases` returns only enabled data sources granted to the current user. Credentials are never included.
- Requesting an existing but unauthorized data-source key returns `403`; an unknown key returns `400`.
- Connection pools are cached by data-source primary key and `updated_at` version. Updating or deleting a configuration closes the old pool, and the next query rebuilds it.
- Password fields are write-only. Admin list responses expose only a password-configured flag.
- The `read_only` connection option is best-effort. Use a dedicated read-only database account for the actual database-level protection.
- Administrators create the connection first, then use `GET /api/admin/datasources/{id}/users` and `PUT /api/admin/datasources/{id}/users` to manage grants.

Typical setup flow:

1. Sign in as an administrator.
2. Create a data source under `/api/admin/datasources` with a unique `dbKey`, JDBC details, dialect, and pool settings.
3. Grant the data source to the required users.
4. Confirm that each user sees the source in `/api/agent/databases`.

## Statistical rules

Statistical rules capture reusable business definitions for a data source, such as formulas, time windows, filters, joins, deduplication, and aggregation constraints. Users with access to the source can view and select rules when asking a question. Only administrators can create, update, or delete them.

Key `stat_rule` fields:

| Field | Meaning |
| --- | --- |
| `data_source_key` | Data source the rule belongs to. |
| `rule_code` | Globally unique rule identifier. |
| `rule_name`, `rule_description` | Display name and usage description. |
| `formula` | Calculation formula. |
| `business_rule` | Filter, time-window, join, deduplication, or grouping constraints. |
| `remark` | Optional notes. |
| `version_no` | Backend-maintained version, incremented on save. |
| `created_by`, `updated_by` | Audit fields populated from the authenticated user. |

Constraints:

- `rule_code` is required and limited to 50 characters.
- `rule_name` is limited to 128 characters; `rule_description` and `remark` to 512; `formula` to 1024; `business_rule` to 4000.
- A rule can have at most 50 input fields. Fields are unique by `source_table + field_name`, ignoring case, and each field description is limited to 512 characters.
- A chat request may contain at most 50 distinct rule IDs.
- Rules are accepted only when the request explicitly specifies a `database`. Sending rules with automatic database selection, using a rule from another data source, or using a rule without access is rejected.
- The backend validates and serializes the selected rules into the planning prompt. `rulePromptJson` is marked `@JsonIgnore`, so clients cannot inject prompt content.
- Rules constrain SQL planning only. Generated SQL still passes `SqlGuard`; rules do not bypass safety checks or affect result formatting and chart generation.

## HTTP API

Except for registration, login, and health, every endpoint requires an authenticated token. Accessing another user's resource, using an admin endpoint as a regular user, or writing a rule without administrator role returns `403`.

### Authentication (`/api/auth`)

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/register` | Submit a registration request. The account is pending and no token is returned. |
| `POST` | `/login` | Sign in and return `token`, `expiresAt`, and user information. |
| `GET` | `/me` | Return the current user. |
| `POST` | `/logout` | Destroy the current token and return `204`. |

### Agent (`/api/agent`)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/health` | Public health status. Authenticated responses also include authorized data-source keys and conversation count. |
| `GET` | `/databases` | List the current user's enabled and authorized data sources without credentials. |
| `POST` | `/chat` | Ask a question. Returns `text/event-stream`; accepts optional `database`, `conversationId`, and `ruleIds`. |
| `DELETE` | `/conversations/{conversationId}` | Clear the in-memory conversation state and return `204`. |

Chat request:

```json
{
  "question": "Show monthly revenue by product category for 2026",
  "database": "demo",
  "conversationId": "optional-existing-id",
  "ruleIds": [12, 18]
}
```

`question` is required and limited to 2000 characters. `conversationId` must match `[A-Za-z0-9_-]{1,64}` when supplied. `database` must be one of the user's authorized keys. `ruleIds` is accepted only with an explicit `database`.

### History (`/api/history`)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/conversations?limit=50` | List the current user's conversations, newest first. |
| `GET` | `/conversations/{conversationId}` | Return a conversation with messages and structured assistant responses. |
| `DELETE` | `/conversations/{conversationId}` | Delete the conversation and its messages, then clear its memory state. Returns `204`. |

### Administrator (`/api/admin`, `ADMIN` only)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/users?page=1&size=10&keyword=&status=all` | Paginate users. Pending accounts are ordered first. Supports keyword and status filters. |
| `POST` | `/users/{userId}/approve` | Approve a pending account or re-enable a disabled account. |
| `POST` | `/users/{userId}/disable` | Disable an account. For a pending account this rejects the registration. An administrator cannot disable their own account. |
| `GET` | `/datasources?page=1&size=10&keyword=&enabled=all` | Paginate global data sources and include assigned-user counts. |
| `POST` | `/datasources` | Create a data source. |
| `PUT` | `/datasources/{dataSourceId}` | Update a data source. An empty password keeps the stored password. |
| `DELETE` | `/datasources/{dataSourceId}` | Delete a data source and all grants. Returns `204`. |
| `GET` | `/datasources/{dataSourceId}/users` | Return all users and the current grant IDs for the authorization dialog. |
| `PUT` | `/datasources/{dataSourceId}/users` | Replace grants with `{"userIds":[...]}`. Returns `204`. |

User and data-source lists return `{ items, total, page, size }`. The maximum page size is 100. `AdminService` checks the administrator role again for every admin operation. Disabling an account revokes all of that user's tokens immediately.

Data-source create/update body:

```json
{
  "dbKey": "sales",
  "name": "Sales database",
  "description": "Read-only sales reporting database",
  "dbType": "mysql",
  "host": "db.example.com",
  "port": 3306,
  "databaseName": "sales",
  "username": "readonly",
  "password": "replace-me",
  "driverClassName": "com.mysql.cj.jdbc.Driver",
  "jdbcUrl": "jdbc:mysql://db.example.com:3306/sales",
  "jdbcParams": "useSSL=true",
  "dialect": "mysql",
  "readOnly": true,
  "maxPoolSize": 3,
  "connectionTimeoutMillis": 10000,
  "initScript": null,
  "enabled": true
}
```

`jdbcUrl` takes precedence when supplied. A blank password on update preserves the existing encrypted password.

### Statistical rules (`/api/rules`)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/?page=1&size=10&dataSourceKey=&keyword=` | Paginate rules visible to the current user. The list omits input-field details. |
| `GET` | `/options?dataSourceKey=demo` | Return compact chat options: `id`, `ruleCode`, `ruleName`, and `ruleDescription`. |
| `GET` | `/{ruleId}` | Return a rule, input fields, `canEdit`, and audit information. |
| `POST` | `/` | Create a rule. Administrator only. The creator is taken from the session. |
| `PUT` | `/{ruleId}` | Replace a rule and its input fields. Administrator only. |
| `DELETE` | `/{ruleId}` | Delete a rule and its input fields. Administrator only. Returns `204`. |

The exact base path is `/api/rules`; `/` in the table denotes the collection root. Rule reads are filtered by data-source access. Rule writes require the `ADMIN` role and access to the target data source. `canEdit` is a UI hint and never replaces backend authorization.

### Errors

Errors use a JSON body with `code`, `message`, and `timestamp`:

```json
{
  "code": "BAD_REQUEST",
  "message": "question must not be empty",
  "timestamp": "2026-01-01T12:00:00+08:00"
}
```

Common status codes:

| Status | Typical code | Meaning |
| --- | --- | --- |
| `400` | `BAD_REQUEST`, `UNSAFE_SQL`, `INVALID_JSON` | Invalid input or rejected SQL. |
| `401` | `UNAUTHORIZED` | Missing, expired, or revoked token. |
| `403` | `FORBIDDEN` | Insufficient role or data-source access. |
| `404` | `NOT_FOUND` | Requested user, data source, rule, or conversation was not found. |
| `500` | `SQL_EXECUTION_ERROR`, `INTERNAL_ERROR` | Query execution or unexpected server failure. |
| `503` | `SERVER_BUSY` | The streaming executor rejected the request. |

## SSE protocol

`POST /api/agent/chat` validates the request synchronously first, so malformed questions or conversation IDs still receive a normal `400` JSON response. Once streaming starts, the response is `text/event-stream;charset=UTF-8`.

| Event | Data | Meaning |
| --- | --- | --- |
| `meta` | Message metadata, data source, status, SQL, and result table | Sent after planning and execution metadata are available; `answer` is still empty. |
| `reasoning` | JSON string containing a reasoning chunk | Zero or more events. |
| `token` | JSON string containing an answer chunk | Zero or more events. The final answer is assembled from these chunks. |
| `done` | Complete `ChatResponse` | Always the final successful event, including failures represented as a response status. |
| `error` | `ChatStreamError` with `code`, `message`, and `status` | Used for an unexpected failure inside the stream. |

SSE `data` fields are serialized as JSON strings so newlines inside chunks cannot corrupt the event framing. Empty reasoning or token chunks are not sent.

`ChatResponse` fields:

| Field | Meaning |
| --- | --- |
| `conversationId` | Conversation identifier, generated when omitted. |
| `status` | `SUCCESS`, `NOT_FEASIBLE`, `UNSAFE_SQL`, `OUTPUT_TOO_LARGE`, or `ERROR`. |
| `answer` | User-facing Markdown answer, including readable explanations for failures. |
| `reasoning` | Complete reasoning text when the model supports it. |
| `reason` | Failure or infeasibility explanation. |
| `database`, `databaseName` | Selected source key and display name. |
| `sql` | Executed SQL when `agent.sql.expose-sql` is enabled; otherwise null. |
| `table` | `{ columns, rows }` structured result when a query ran successfully. |
| `charts` | Up to four chart specifications; no duplicated data. |
| `rowCount`, `truncated` | Row count and whether the row limit was reached. |
| `elapsedMs` | End-to-end duration. |
| `warnings` | Non-fatal warnings. |
| `timestamp` | Response generation time. |

The backend sends `Cache-Control: no-cache` and `X-Accel-Buffering: no`, and uses a 600-second SSE emitter timeout. Reverse proxies must disable buffering for the chat endpoint.

## Charts and display names

A chart specification describes how to draw a result, not the data itself:

```json
{
  "type": "bar",
  "title": "Customers by region",
  "xField": "REGION",
  "yFields": ["CUSTOMER_COUNT"],
  "unit": "customers"
}
```

- Supported types are `bar`, `line`, `pie`, and `scatter`; a pie chart uses one numeric field.
- `xField` and `yFields` must exist in the response table. Invalid specifications are discarded individually.
- The backend accepts multiple `chart` code blocks, a JSON array inside one block, or `{"charts":[...]}`. Each item is validated independently.
- At most four charts are returned. Duplicate chart types are discouraged by the prompt but are not a security issue.
- Charts do not include data, so the frontend reads values from `table`.
- Model-generated ECharts options, HTML, JavaScript, and SVG are never trusted or executed.

Display-name sanitization:

- `SchemaMetadata` reads table and column comments from JDBC metadata. Result columns prefer `table.column` comments, then a unique column name, then the raw column name. Duplicate display names receive suffixes such as `_2`.
- The formatting prompt receives business column names and does not include the SQL, reducing leakage of physical identifiers into the answer.
- `DisplayNameSanitizer` performs case-insensitive, word-boundary replacement in the answer, reasoning, and chart titles. It prefers qualified `table.column` matches, then unambiguous column names and table comments.
- Streaming output uses `DisplayNameSanitizer.Streaming`: a trailing identifier fragment that may be split across chunks is retained until a delimiter or stream end, preventing names such as `user_` plus `id` from leaking.
- If comments are missing or ambiguous, the original identifier is retained rather than guessing.

## Build and test

Run the unit and flow tests:

```bash
mvn test
```

Tests tagged `integration` are excluded by default because they may call a real model or database. To run them after configuring a real environment:

```bash
mvn test -DexcludedGroups= -Dgroups=integration
```

Build the executable jar:

```bash
mvn clean package
java -jar target/RinBIBackend-1.0-SNAPSHOT.jar
```

The build excludes DevTools from the executable archive. Production configuration should be supplied through environment variables or an external profile, not by editing the packaged jar.

## Project structure

```text
src/main/java/org/rin/
├─ AppStart.java             # Spring Boot entry point and retry enablement
├─ agent/                    # Agent pipeline, LLM calls, streaming, memory, sanitization, DTOs
├─ admin/                    # User approval, global data-source administration and grants
├─ auth/                     # Registration, login, tokens, password hashing, user mapper
├─ config/                   # Configuration properties, LLM, CORS, bootstrap, stream executor
├─ db/                       # Data-source registry, schema metadata, SQL guard and query execution
├─ history/                  # Conversation and message persistence and history DTOs
├─ rule/                     # Statistical-rule service, entities, mappers and DTOs
├─ security/                 # AES-GCM encryption for data-source passwords
├─ exception/                # Business exceptions
└─ web/                      # Global exception handler

src/main/resources/
├─ application.yml           # Main configuration and active profile
├─ application-dev.yml       # Port, metadata database, LLM, limits and logging
├─ logback-dev.xml           # Log4j2 development configuration
├─ db/
│  ├─ rinbidb-schema.sql     # Idempotent MySQL metadata schema
│  ├─ demo.sql               # H2 demo data
│  └─ postgres-stock-testdata.sql
└─ prompts/
   ├─ plan-system.txt        # Query-planning system prompt
   └─ format-system.txt      # Answer-formatting system prompt

src/test/
├─ java/org/rin/             # Flow tests and unit tests
└─ resources/db/             # Test H2 schema
```

## Production checklist

- Replace the default administrator password and set a strong `RINBI_SECRET`.
- Disable bootstrap after initial provisioning, or at minimum disable the demo data source.
- Set `agent.sql.expose-sql=false` unless SQL exposure is explicitly required.
- Create dedicated read-only database accounts for every query data source. Treat `SqlGuard` as an additional layer, not the only one.
- Restrict `agent.web.allowed-origins` to the production frontend origin.
- Terminate TLS at the reverse proxy and disable response buffering for the SSE endpoint.
- Use HTTPS and avoid logging prompts, answers, credentials, or full connection strings.
- Back up the metadata MySQL database and protect its credentials.
- Replace in-memory token storage with a shared store if you run multiple backend instances.
- Size the streaming executor, Hikari pools, MySQL connections, and reverse-proxy timeouts for the expected concurrency.
- Rotate the LLM key and data-source credentials regularly; changing the AES secret requires re-entering data-source passwords.
- Review the schema migration script before upgrading a database that may contain legacy tables.

## Related documentation

- [Chinese project README](../README.md)
- [English frontend README](../RinBIFrontend/README.en.md)
- [Chinese frontend README](../RinBIFrontend/README.md)
- [License](../LICENSE)
