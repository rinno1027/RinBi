# RinBI Frontend

[Chinese](./README.md) | **English**

RinBI Frontend is the web client for the RinBI natural-language data assistant. After signing in, a user selects an authorized data source, asks a question in natural language, and receives a streamed explanation plus a structured result table, optional SQL tracing, and optional ECharts visualizations. The backend owns authentication, SQL safety checks, query execution, conversation persistence, data-source authorization, and statistical rules.

This document describes the frontend only. Backend setup and API details are in [RinBIBackend/README.en.md](../RinBIBackend/README.en.md).

## Table of contents

- [Features](#features)
- [Tech stack](#tech-stack)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Environment variables](#environment-variables)
- [Project structure](#project-structure)
- [Authentication and session handling](#authentication-and-session-handling)
- [Admin entries](#admin-entries)
- [Chat and streaming](#chat-and-streaming)
- [History, data sources, and rules](#history-data-sources-and-rules)
- [Structured results and charts](#structured-results-and-charts)
- [PDF export](#pdf-export)
- [Build and deployment](#build-and-deployment)
- [Validation](#validation)
- [Security notes](#security-notes)
- [Related documentation](#related-documentation)

## Features

- Login, self-service registration request, pending-approval messaging, and logout.
- SSE chat powered by `fetch` and `ReadableStream`, with live reasoning and answer chunks.
- Data-source selector with an automatic mode in which the model chooses the database.
- Per-data-source statistical-rule picker, selected by default and limited to 50 rules.
- Server-side conversation history with restore and delete actions.
- Structured result tables with pagination and optional SQL tracing.
- Multiple responsive ECharts charts generated only from allow-listed `ChartSpec` fields.
- Markdown answers sanitized with DOMPurify; chart JSON is stripped during streaming.
- PDF export of the answer body and charts, loaded on demand.
- Separate admin pages for user management and data-source management.
- Responsive layout for desktop and narrow screens.

## Tech stack

| Area | Technology |
| --- | --- |
| Framework | Vue 3.2 with `<script setup>` |
| Build | Vite 2.9.18 |
| UI | Element Plus 2.2.36 |
| Markdown | markdown-it 12.3.2 and DOMPurify 2.4.7 |
| Charts | ECharts 5.4.3, loaded lazily through `ChartPanel` |
| PDF | html2pdf.js, imported dynamically only when exporting |
| HTTP | Native `fetch` with JSON helpers and a streaming SSE parser |

## Requirements

- Node.js 14.16 or newer.
- npm with lockfile support (`npm ci` is the recommended install command).
- A running RinBI backend. The default development address is `http://localhost:8080`.
- A browser with `fetch`, `ReadableStream`, `ResizeObserver`, and ES module support.

## Quick start

1. Start the backend from the repository root:

```bash
cd RinBIBackend
mvn spring-boot:run
```

2. In another terminal, install and start the frontend:

```powershell
cd RinBIFrontend
npm ci
Copy-Item .env.example .env.local
npm run dev
```

On Linux or macOS, use `cp .env.example .env.local` instead of `Copy-Item`.

3. Open <http://localhost:5173>.

The Vite development server listens on `0.0.0.0:5173` with `strictPort` enabled and proxies `/api` to `VITE_PROXY_TARGET`, which defaults to `http://localhost:8080`.

The first page is the sign-in screen. The backend bootstrap account is `admin / admin123` by default; change it through the backend environment variables before any real deployment. The registration tab submits a pending account, and an administrator must approve it in the user-management page before the account can sign in.

## Environment variables

The frontend reads Vite variables from `.env.local` during development and from build-time environment variables for production builds.

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_API_BASE_URL` | empty | When empty, requests use the same-origin `/api` path and the Vite proxy. For a split-domain deployment, set the full backend URL, for example `https://rinbi.example.com`. |
| `VITE_PROXY_TARGET` | `http://localhost:8080` | Development-server only. Target of the `/api` proxy. |

`VITE_API_BASE_URL` has trailing slashes removed at runtime. Only variables prefixed with `VITE_` are exposed to browser code, so never put secrets in a frontend environment file.

## Project structure

```text
src/
├─ App.vue                    # Authentication state, chat state, SSE flow, history and workspace layout
├─ main.js                    # Vue application entry point
├─ api/
│  ├─ http.js                 # API base URL, JSON request helper, ApiError and 401 handling
│  ├─ auth.js                 # Registration request, login, current user and logout
│  ├─ admin.js                # User pagination/approval and data-source CRUD/access APIs
│  ├─ history.js              # Conversation list, detail and deletion
│  ├─ rules.js                # Rule pagination, detail, options and CRUD
│  └─ agent.js                # Health, data sources, SSE chat and conversation clearing
├─ components/
│  ├─ LoginView.vue           # Login/registration form and validation
│  ├─ AdminPanel.vue          # Admin user pagination, approval and enable/disable controls
│  ├─ DataSourcePanel.vue     # Admin data-source CRUD and per-user authorization dialog
│  ├─ RulePanel.vue           # Rule list, filters, editor and input-field maintenance
│  ├─ ChatMessage.vue         # One answer, Markdown, charts, table and PDF export action
│  ├─ ChartPanel.vue          # Converts ChartSpec entries into ECharts options
│  ├─ ChartCanvas.vue         # ECharts instance lifecycle and resize handling
│  └─ QueryResultTable.vue    # Result table pagination and SQL tracing
├─ styles/                    # Base, chat, authentication, admin, rule and responsive styles
└─ utils/
   ├─ session.js              # localStorage token, authorization headers and 401 event
   ├─ format.js               # Date, elapsed-time, status and cell formatting
   ├─ markdown.js             # Markdown rendering, HTML sanitizing and chart-block removal
   └─ pdf.js                  # On-demand PDF export of the answer and charts
```

The app is a single-page application without a client-side router. `App.vue` switches between the chat workspace, user management, data-source management, and the rules page.

## Authentication and session handling

1. On startup, the app reads the token from `localStorage` under the key `rinbi.token`.
2. If a token exists, the app calls `GET /api/auth/me` to validate it and load the workspace. If the token is missing or invalid, `LoginView` is displayed.
3. Login calls `POST /api/auth/login`. The returned token is stored in `localStorage`; the app then loads data sources, history, and workspace data.
4. Registration calls `POST /api/auth/register`. The backend creates a pending account and does not issue a token. The UI explains that an administrator must approve the account.
5. JSON requests use `src/api/http.js#request`, which attaches `Authorization: Bearer <token>` automatically. SSE requests in `src/api/agent.js` attach the same header.
6. Any `401` response clears the token, emits the `rinbi:unauthorized` event, resets application state, and returns the user to the sign-in page. A `403` is reported on the current page without clearing the session.
7. Logout calls `POST /api/auth/logout`; the local token is cleared even if the backend request fails.

Tokens are opaque strings. The frontend never parses or extends them. Backend restarts invalidate in-memory tokens, and administrator account disabling revokes active sessions. In both cases the next request receives `401` and the UI returns to the sign-in page.

The request helper also handles timeouts and network failures with user-facing `ApiError` values. The default JSON timeout is 120 seconds; individual API functions use shorter timeouts where appropriate.

## Admin entries

When `GET /api/auth/me` returns `user.role === "ADMIN"`, the header exposes separate entries for user management and data-source management. Regular users do not render these entries, but the backend `AdminService` always enforces the `ADMIN` role; hiding a button is not the security boundary.

User management, data-source management, and the rules page are mutually exclusive top-level views. Opening one returns to the chat workspace when it is closed. The "clear conversation" action is hidden while an admin page is open.

### User management

- `GET /api/admin/users?page=1&size=10` loads a page of users. The backend prioritizes pending accounts, then active accounts, then disabled accounts.
- Filters support username or display-name keywords and user status.
- `POST /api/admin/users/{id}/approve` approves a pending account or re-enables a disabled account.
- `POST /api/admin/users/{id}/disable` disables an account. For a pending registration this is equivalent to rejection. The current administrator cannot disable their own account.
- Approval and disabling immediately revoke the target user's active backend tokens.

### Data-source management

`DataSourcePanel.vue` is separate from user management and uses the same admin API module:

- `GET /api/admin/datasources?page=1&size=10` lists global data sources with pagination, keyword filtering, enabled-state filtering, and assigned-user counts.
- `POST /api/admin/datasources` creates a data source.
- `PUT /api/admin/datasources/{id}` updates a data source. An empty password keeps the existing password.
- `DELETE /api/admin/datasources/{id}` deletes the data source and its authorization rows.
- `GET /api/admin/datasources/{id}/users` returns all users plus the currently authorized user IDs.
- `PUT /api/admin/datasources/{id}/users` replaces the authorization list with the submitted `userIds`.

The list never returns stored passwords; it exposes only whether a password is configured. New passwords are encrypted by the backend with AES-GCM before they are stored.

## Chat and streaming

The chat request is `POST /api/agent/chat` with an optional `conversationId`, an optional `database`, and optional `ruleIds`:

```json
{
  "question": "Show monthly revenue by product category for 2026",
  "database": "demo",
  "ruleIds": [12, 18]
}
```

The frontend sends `database` only when the user selected a specific source. If the selector is in automatic mode, the field is omitted and the model chooses among the authorized data sources. Rules are not loaded or sent in automatic mode.

The request is implemented with `fetch` rather than `EventSource`, because `EventSource` cannot attach an `Authorization` header. The client accepts `text/event-stream, application/json`; this also allows synchronous validation failures to return a normal JSON error. The frontend waits up to 660 seconds, slightly longer than the backend SSE timeout, so the server can deliver its own timeout or error event first.

SSE events:

| Event | Payload | Frontend behavior |
| --- | --- | --- |
| `meta` | Conversation, database, status, SQL, and structured table metadata | Updates message metadata and the result table |
| `reasoning` | A reasoning text chunk | Appends the chunk to the collapsible reasoning panel |
| `token` | An answer text chunk | Appends the chunk and renders Markdown incrementally |
| `done` | Complete `ChatResponse` with `answer`, `reasoning`, `charts`, and table data | Replaces the streaming message with the final result |
| `error` | `{ code, message, status }` emitted inside the stream | Converts the stream into an error message bubble |

The frontend allows only one chat request at a time. While a request is running, the input and send button are disabled. A new conversation has no `conversationId`; the backend creates one and returns it in `meta` and `done`. The ID is persisted locally so the conversation can be continued after a reload.

The client handles stream interruption, timeout, non-2xx responses, and missing `done` events as explicit errors. Only `401` resets authentication; all other failures remain local to the chat page.

## History, data sources, and rules

### History

- `GET /api/history/conversations?limit=50` loads the current user's recent conversations, newest first.
- `GET /api/history/conversations/{id}` loads the complete conversation, including user messages and structured assistant responses.
- `DELETE /api/history/conversations/{id}` deletes the conversation and its messages.
- Selecting a history item restores Markdown, table, charts, and SQL, and sets the active `conversationId` for follow-up questions.
- The history list is refreshed after each completed answer. If the previously active conversation no longer exists, it is removed from the list and the chat area is reset.

### Data sources

`GET /api/agent/databases` returns only data sources that are enabled and authorized for the signed-in user. The response contains the key, display name, description, and dialect, but no credentials.

The selector offers an automatic mode and the authorized sources. When a specific source is selected, the app loads its rules and defaults to selecting all of them. If no data source is available, the selector is disabled and the UI shows an empty-state hint.

### Statistical rules

The rules page is available to every signed-in user and is always scoped to data sources the user can access.

- `GET /api/rules?page=1&size=10&dataSourceKey=...&keyword=...` loads paginated rules.
- `GET /api/rules/options?dataSourceKey=...` loads the compact options used by the chat rule picker.
- `GET /api/rules/{id}` loads a rule and its input fields.
- `POST /api/rules`, `PUT /api/rules/{id}`, and `DELETE /api/rules/{id}` are administrator-only operations.
- Every user may view rules; only administrators see create, edit, and delete actions. The backend `canEdit` flag is for UI state only and does not replace server-side authorization.
- The chat picker is limited to 50 selected rules. It supports search, select-all, clear-all, and an inline retry state. Selecting rules affects only subsequent questions for the current data source and is not written into conversation history.
- Rules are not available when the data source is in automatic mode, because a rule belongs to one data source and the model may choose a different one.

## Structured results and charts

The final `ChatResponse` contains an `answer` in Markdown and a `table` with parallel `columns` and `rows` arrays:

```json
{
  "table": {
    "columns": ["REGION", "CUSTOMER_COUNT"],
    "rows": [["East", "4"], ["North", "1"]]
  },
  "charts": [
    {
      "type": "bar",
      "title": "Customers by region",
      "xField": "REGION",
      "yFields": ["CUSTOMER_COUNT"],
      "unit": "customers"
    },
    {
      "type": "pie",
      "title": "Customer share by region",
      "xField": "REGION",
      "yFields": ["CUSTOMER_COUNT"],
      "unit": "customers"
    }
  ]
}
```

Chart rules:

- Supported types are `bar`, `line`, `pie`, and `scatter`.
- `xField` and `yFields` must match columns in the same `table`, compared case-insensitively with trimmed whitespace.
- The backend returns at most four `ChartSpec` entries.
- Charts contain no data. The frontend reads values from `table`, so payload size is not duplicated.
- Invalid chart types, missing fields, or charts with no valid numeric series are skipped individually; the answer, table, and other charts remain available.
- Chart cards use a responsive grid with a maximum of two columns on wide screens and one column at `640px` or narrower. Only the first 200 rows are used for rendering.
- `ChartPanel` is loaded with `defineAsyncComponent`; ECharts is not downloaded when an answer has no charts.
- The frontend never inserts model-generated ECharts options, HTML, JavaScript, or SVG into the DOM. Options are built only from allow-listed `ChartSpec` fields.

Markdown is rendered with `markdown-it` and sanitized with DOMPurify. During streaming, incomplete or complete `chart` code blocks are stripped first so raw chart JSON never flashes in the answer.

## PDF export

An answer with text or charts exposes a PDF export action. The export uses `html2pdf.js`, imported dynamically only when the action is used. The generated document includes the answer body and rendered chart images; reasoning text, SQL, and table details are intentionally excluded. Charts are exported from the current ECharts bitmap so the PDF matches the visible result.

## Build and deployment

```powershell
npm run build
npm run preview
```

The production build is written to `dist/`. Deploy `dist/` with any static file server and route `/api` to the backend.

For a same-origin deployment, configure a reverse proxy:

```nginx
location /api/ {
    proxy_pass http://rinbi-backend:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_read_timeout 660s;
}
```

For a split-domain deployment, set `VITE_API_BASE_URL` at build time and make sure the backend CORS configuration allows the frontend origin, the `Authorization` header, and the required methods. Do not cache SSE responses; the backend already sends `Cache-Control: no-cache` and `X-Accel-Buffering: no`, but the proxy must also disable response buffering for `/api/agent/chat`.

## Validation

The project currently has no frontend unit-test script. Validate changes with:

```powershell
npm run build
```

Then perform a browser smoke test with a running backend:

1. Sign in as an administrator.
2. Open user management and data-source management and confirm they render as separate views.
3. Create or edit a data source, open the authorization dialog, and save a user list.
4. Select a specific data source, confirm its rules load and are selected by default, then ask a question.
5. Confirm streamed reasoning and answer text, table rendering, SQL visibility, chart rendering, history restore, and PDF export.
6. Sign in as a regular user and confirm admin entries are absent while authorized data sources and rules remain available.

## Security notes

- Markdown is sanitized with DOMPurify. Chart configuration is generated from allow-listed fields, not from model-provided HTML or JavaScript.
- The token lives in `localStorage`, which is readable by JavaScript. Enable a strict Content Security Policy in production and keep all rendering paths sanitized.
- Use HTTPS in production so tokens and question data are not sent in clear text.
- Do not place backend credentials or LLM API keys in frontend environment variables or source files.
- A hidden admin control is not an authorization mechanism. The backend must remain the source of truth for role and data-source checks.
- Disable proxy buffering and caching for SSE chat responses.

## Related documentation

- [Chinese frontend README](./README.md)
- [English backend README](../RinBIBackend/README.en.md)
- [Project README (Chinese)](../README.md)
