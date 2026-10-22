package org.rin.agent;

import org.rin.agent.dto.ChatRequest;
import org.rin.agent.dto.ChatResponse;
import org.rin.agent.dto.QueryPlan;
import org.rin.agent.dto.QueryTable;
import org.rin.agent.dto.ReasoningStreamResult;
import org.rin.config.AgentProperties;
import org.rin.db.*;
import org.rin.exception.BadRequestException;
import org.rin.history.HistoryService;
import org.rin.history.HistoryService.HistoryExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Agent 主流程：问题 → 生成计划 → 只读校验 → 执行 → 整理结果。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int MAX_QUESTION_LENGTH = 2000;
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Autowired
    private LlmService llmService;

    @Autowired
    private SchemaService schemaService;

    @Autowired
    private SqlGuard sqlGuard;

    @Autowired
    private QueryExecutor queryExecutor;

    @Autowired
    private ConversationMemory conversationMemory;

    @Autowired
    private DatabaseRegistry databaseRegistry;

    @Autowired
    private AgentProperties properties;

    @Autowired
    private ChartSpecParser chartSpecParser;

    @Autowired
    private HistoryService historyService;

    public ChatResponse chat(Long userId, ChatRequest request) {
        return chat(userId, request, null);
    }

    /**
     * 执行一次对话。listener 非空时，结果整理阶段会流式回调模型的思考过程和答案分片；
     * 完整答案仍随 ChatResponse 一并返回，便于前端最终对齐。
     */
    public ChatResponse chat(Long userId, ChatRequest request, ChatStreamListener listener) {
        return execute(userId, prepare(userId, request), listener);
    }

    /**
     * 校验并补全请求。SSE 接口需要在返回 SseEmitter 之前同步调用，
     * 从而让 question/conversationId 不合法时仍然返回 HTTP 400。
     */
    public ChatRequest prepare(Long userId, ChatRequest request) {
        String question = request == null || request.getQuestion() == null
                ? "" : request.getQuestion().trim();
        if (question.isEmpty()) {
            throw new BadRequestException("question 不能为空");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new BadRequestException("question 长度不能超过 " + MAX_QUESTION_LENGTH + " 字符");
        }

        List<String> availableDatabases = databaseRegistry.getKeys(userId);
        String requestedDatabase = normalize(request.getDatabase());
        if (requestedDatabase != null && !availableDatabases.contains(requestedDatabase)) {
            throw new BadRequestException("未配置数据库: " + requestedDatabase
                    + "，可用数据库: " + availableDatabases);
        }

        String conversationId = normalize(request.getConversationId());
        if (conversationId == null) {
            conversationId = conversationMemory.createConversationId();
        } else if (!CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            throw new BadRequestException("conversationId 只能包含字母、数字、下划线和短横线，长度 1-64");
        }

        ChatRequest prepared = new ChatRequest();
        prepared.setQuestion(question);
        prepared.setDatabase(requestedDatabase);
        prepared.setConversationId(conversationId);
        return prepared;
    }

    /**
     * 执行已通过 {@link #prepare(Long, ChatRequest)} 校验的请求。
     */
    public ChatResponse execute(Long userId, ChatRequest prepared, ChatStreamListener listener) {
        long start = System.currentTimeMillis();
        String conversationId = prepared.getConversationId();
        ChatResponse response;
        try {
            response = doChat(userId, prepared.getQuestion(), prepared.getDatabase(),
                    conversationId, start, listener);
        } catch (BadRequestException e) {
            throw e;
        } catch (UnsafeSqlException e) {
            response = failure(conversationId, AgentStatus.UNSAFE_SQL, "查询被安全策略拦截",
                    e.getMessage(), start);
            sanitizeReason(userId, prepared.getDatabase(), response);
        } catch (SqlExecutionException e) {
            log.warn("查询执行失败: {}", e.getMessage());
            response = failure(conversationId, AgentStatus.ERROR, "查询执行失败，请稍后重试",
                    e.getMessage(), start);
            // 数据库异常常带出真实表名/字段名，返回给前端前同样做业务名替换。
            sanitizeReason(userId, prepared.getDatabase(), response);
        } catch (RuntimeException e) {
            log.error("Agent 处理失败", e);
            response = failure(conversationId, AgentStatus.ERROR, "处理请求时发生错误，请稍后重试",
                    safeMessage(e), start);
            sanitizeReason(userId, prepared.getDatabase(), response);
        }
        finish(userId, prepared, response);
        return response;
    }

    private ChatResponse doChat(Long userId, String question, String requestedDatabase,
                                String conversationId, long start,
                                ChatStreamListener listener) {
        String schemaText = requestedDatabase == null
                ? schemaService.getAllSchemas(userId)
                : schemaService.getSchema(userId, requestedDatabase);
        hydrateMemory(userId, conversationId);
        String historyText = conversationMemory.getHistoryText(
                ConversationMemory.memoryKey(userId, conversationId));

        QueryPlan plan = llmService.plan(question, requestedDatabase, schemaText, historyText);
        if (!plan.isFeasiblePlan()) {
            String reason = StringUtils.hasText(plan.getReason())
                    ? plan.getReason().trim() : "当前数据无法支持该问题";
            ChatResponse response = baseResponse(conversationId, AgentStatus.NOT_FEASIBLE,
                    reason, reason, start);
            response.setDatabase(requestedDatabase);
            // 未指定数据源时模型可能引用任一已授权库的表名，用全部数据源的注释做兜底。
            DisplayNameSanitizer sanitizer = identifierSanitizer(userId, requestedDatabase);
            response.setAnswer(sanitizer.sanitize(response.getAnswer()));
            response.setReason(sanitizer.sanitize(response.getReason()));
            return response;
        }

        String databaseKey = resolveDatabase(userId, requestedDatabase, plan.getDatabase());
        if (databaseKey == null) {
            String reason = "模型选择了未配置的数据库: " + plan.getDatabase()
                    + "，可用数据库: " + databaseRegistry.getKeys(userId);
            return baseResponse(conversationId, AgentStatus.ERROR,
                    "无法确定要查询的数据库，请指定 database 后重试", reason, start);
        }

        if (!StringUtils.hasText(plan.getSql())) {
            String reason = "模型没有生成可执行的 SQL";
            ChatResponse response = baseResponse(conversationId, AgentStatus.ERROR,
                    "未能生成查询语句，请换一种问法后重试", reason, start);
            response.setDatabase(databaseKey);
            response.setDatabaseName(databaseName(userId, databaseKey));
            return response;
        }

        String safeSql;
        try {
            safeSql = sqlGuard.validate(plan.getSql());
        } catch (UnsafeSqlException e) {
            ChatResponse response = baseResponse(conversationId, AgentStatus.UNSAFE_SQL,
                    "该请求生成了非只读 SQL，已被安全策略拦截。请改为查询类问题。",
                    e.getMessage(), start);
            response.setDatabase(databaseKey);
            response.setDatabaseName(databaseName(userId, databaseKey));
            sanitizeReason(userId, databaseKey, response);
            applySql(response, plan.getSql());
            response.getWarnings().add("系统只允许执行 SELECT / WITH ... SELECT 只读查询");
            return response;
        }

        QueryResult result = queryExecutor.execute(userId, databaseKey, safeSql);
        if (result.isTooLarge()) {
            int maxChars = properties.getSql().getMaxResultChars() == null
                    ? 30000 : properties.getSql().getMaxResultChars();
            String reason = "查询结果超过 " + maxChars + " 字符上限，未返回数据";
            ChatResponse response = baseResponse(conversationId, AgentStatus.OUTPUT_TOO_LARGE,
                    "查询结果数据量过大，已停止返回。请增加过滤条件、减少列数，或分步查询后重试。",
                    reason, start);
            response.setDatabase(databaseKey);
            response.setDatabaseName(databaseName(userId, databaseKey));
            applySql(response, safeSql);
            response.setRowCount(result.getRowCount());
            response.setTruncated(true);
            response.getWarnings().add(reason);
            return response;
        }

        // 用字段注释替代真实列名，避免最终回答和图表出现数据库字段名。
        SchemaMetadata metadata = loadMetadata(userId, databaseKey);
        QueryTable table = QueryTable.builder()
                .columns(metadata.displayColumns(result.getColumnRefs(), result.getColumns()))
                .rows(new ArrayList<>(result.getRows()))
                .build();
        ChatResponse response = baseResponse(conversationId, AgentStatus.SUCCESS, null, null, start);
        response.setDatabase(databaseKey);
        response.setDatabaseName(databaseName(userId, databaseKey));
        applySql(response, safeSql);
        response.setTable(table);
        response.setRowCount(result.getRowCount());
        response.setTruncated(result.isTruncated());

        // 先把数据源、SQL、结构化结果发给前端，再开始流式输出思考过程。
        notifyMeta(listener, response);

        // 模型流式输出可能写出真实字段名，下发给前端前就要替换，不能等到 done 事件。
        DisplayNameSanitizer sanitizer = new DisplayNameSanitizer(metadata.identifierLabels());
        DisplayNameSanitizer.Streaming reasoningStream = sanitizer.streaming();
        DisplayNameSanitizer.Streaming answerStream = sanitizer.streaming();

        String answer;
        List<String> warnings = new ArrayList<>();
        try {
            if (listener != null) {
                ReasoningStreamResult formatted = llmService.formatWithReasoning(
                        question, table, result.getRowCount(), result.isTruncated(), false,
                        chunk -> notifyReasoning(listener, reasoningStream.accept(chunk)),
                        chunk -> notifyToken(listener, answerStream.accept(chunk)));
                answer = formatted.getContent();
                response.setReasoning(formatted.getReasoning());
            } else {
                answer = llmService.format(question, table, result.getRowCount(),
                        result.isTruncated(), false);
            }
        } catch (RuntimeException e) {
            log.warn("结果整理失败，使用本地 Markdown 表格回退: {}", e.getMessage());
            answer = fallbackMarkdown(table, result.getRowCount(), result.isTruncated());
            warnings.add("模型整理结果失败，已返回系统生成的表格");
        } finally {
            // 输出被流式清洗器保留下来的最后一个标识符，保证前端拼接结果与最终答案一致。
            notifyReasoning(listener, reasoningStream.flush());
            notifyToken(listener, answerStream.flush());
        }

        // 解析答案末尾的 chart 代码块：剥离原始 JSON，只把校验通过的图表描述下发。
        // 图表属于增强信息，解析失败不能影响文字答案。
        try {
            ChartSpecParser.Result chartResult = chartSpecParser.extract(answer, table);
            answer = chartResult.getMarkdown();
            response.setCharts(chartResult.getCharts());
        } catch (RuntimeException e) {
            log.warn("图表解析失败，已忽略图表: {}", e.getMessage());
        }

        // 兜底：即使模型在洞察或图表标题里写了真实字段名，也替换为业务名称。
        answer = sanitizer.sanitize(answer);
        response.setReasoning(sanitizer.sanitize(response.getReasoning()));
        response.getCharts().forEach(chart -> chart.setTitle(sanitizer.sanitize(chart.getTitle())));

        response.setAnswer(answer);
        response.setElapsedMs(System.currentTimeMillis() - start);
        response.setTimestamp(OffsetDateTime.now().format(TIMESTAMP_FORMAT));
        response.getWarnings().addAll(warnings);
        if (result.getMessage() != null) {
            response.getWarnings().add(result.getMessage());
        }
        log.info("Agent 查询成功: conversation={}, database={}, rows={}, charts={}, elapsedMs={}",
                conversationId, databaseKey, result.getRowCount(), response.getCharts().size(),
                System.currentTimeMillis() - start);
        return response;
    }

    /**
     * 构造输出清洗器：指定数据源时只使用该库，未指定时合并全部已授权数据源的注释。
     * 同名标识符按首次出现的注释替换，优先避免原文泄漏，而不是追求跨库语义完全一致。
     */
    private DisplayNameSanitizer identifierSanitizer(Long userId, String databaseKey) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (databaseKey != null) {
            labels.putAll(loadMetadata(userId, databaseKey).identifierLabels());
        } else {
            for (String key : databaseRegistry.getKeys(userId)) {
                loadMetadata(userId, key).identifierLabels().forEach(labels::putIfAbsent);
            }
        }
        return new DisplayNameSanitizer(labels);
    }

    /**
     * 异常原因同样可能包含真实表名/字段名，返回前端前做一次兜底替换；
     * 元数据不可用时保持原文，不能因为清洗失败而吞掉错误响应。
     */
    private void sanitizeReason(Long userId, String databaseKey, ChatResponse response) {
        if (response == null || !StringUtils.hasText(response.getReason())) {
            return;
        }
        try {
            response.setReason(identifierSanitizer(userId, databaseKey)
                    .sanitize(response.getReason()));
        } catch (RuntimeException e) {
            log.warn("清洗错误信息失败，保持原文: {}", e.getMessage());
        }
    }

    /**
     * 读取字段注释映射；元数据读取失败不能影响查询结果返回，此时回退到原始列名。
     */
    private SchemaMetadata loadMetadata(Long userId, String databaseKey) {
        try {
            return schemaService.getMetadata(userId, databaseKey);
        } catch (RuntimeException e) {
            log.warn("读取字段注释失败，回退到原始列名: {}", e.getMessage());
            return SchemaMetadata.empty();
        }
    }

    /**
     * 是否在响应中暴露真实 SQL。SQL 本身包含库表/字段名，需要隐藏结构时可关闭。
     */
    private void applySql(ChatResponse response, String sql) {
        if (!Boolean.FALSE.equals(properties.getSql().getExposeSql())) {
            response.setSql(sql);
        }
    }

    private void notifyMeta(ChatStreamListener listener, ChatResponse response) {
        if (listener == null) {
            return;
        }
        try {
            listener.onMeta(response);
        } catch (RuntimeException e) {
            log.debug("推送流式元数据失败: {}", e.getMessage());
        }
    }

    private void notifyReasoning(ChatStreamListener listener, String chunk) {
        if (listener == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        try {
            listener.onReasoning(chunk);
        } catch (RuntimeException e) {
            log.debug("推送思考分片失败: {}", e.getMessage());
        }
    }

    private void notifyToken(ChatStreamListener listener, String chunk) {
        if (listener == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        try {
            listener.onToken(chunk);
        } catch (RuntimeException e) {
            log.debug("推送答案分片失败: {}", e.getMessage());
        }
    }

    private ChatResponse baseResponse(String conversationId, AgentStatus status,
                                      String answer, String reason, long start) {
        return ChatResponse.builder()
                .conversationId(conversationId)
                .status(status)
                .answer(answer)
                .reason(reason)
                .elapsedMs(System.currentTimeMillis() - start)
                .timestamp(OffsetDateTime.now().format(TIMESTAMP_FORMAT))
                .warnings(new ArrayList<>())
                .build();
    }

    private ChatResponse failure(String conversationId, AgentStatus status, String answer,
                                 String reason, long start) {
        return baseResponse(conversationId, status, answer, reason, start);
    }

    /**
     * 优先使用客户端指定的数据库；否则使用模型选择，并兼容按数据库显示名匹配。
     */
    private String resolveDatabase(Long userId, String requestedDatabase, String planDatabase) {
        if (requestedDatabase != null) {
            return requestedDatabase;
        }
        List<DataSourceDefinition> available = databaseRegistry.listForUser(userId);
        if (StringUtils.hasText(planDatabase)) {
            String candidate = planDatabase.trim();
            for (DataSourceDefinition definition : available) {
                if (definition.getKey().equals(candidate)) {
                    return definition.getKey();
                }
            }
            for (DataSourceDefinition definition : available) {
                if (definition.getName() != null
                        && definition.getName().equalsIgnoreCase(candidate)) {
                    return definition.getKey();
                }
            }
        }
        if (available.size() == 1) {
            return available.get(0).getKey();
        }
        return null;
    }

    private String databaseName(Long userId, String databaseKey) {
        if (databaseKey == null) {
            return null;
        }
        for (DataSourceDefinition definition : databaseRegistry.listForUser(userId)) {
            if (definition.getKey().equals(databaseKey)) {
                return definition.getName() == null ? databaseKey : definition.getName();
            }
        }
        return databaseKey;
    }

    /**
     * 对话结束后统一写入进程内记忆与历史库。
     */
    private void finish(Long userId, ChatRequest request, ChatResponse response) {
        String conversationId = request.getConversationId();
        String question = request.getQuestion();
        try {
            conversationMemory.addExchange(
                    ConversationMemory.memoryKey(userId, conversationId),
                    question, response.getAnswer());
        } catch (RuntimeException e) {
            log.debug("写入会话记忆失败: {}", e.getMessage());
        }
        try {
            historyService.recordExchange(userId, conversationId, question,
                    response.getDatabase(), response);
        } catch (RuntimeException e) {
            log.warn("写入历史对话失败: {}", e.getMessage());
        }
    }

    /**
     * 进程内记忆为空时，从历史库恢复最近若干轮问答，保证重启后继续追问仍有上下文。
     */
    private void hydrateMemory(Long userId, String conversationId) {
        String memoryKey = ConversationMemory.memoryKey(userId, conversationId);
        if (conversationMemory.exists(memoryKey)) {
            return;
        }
        try {
            for (HistoryExchange exchange : historyService.recentExchanges(
                    userId, conversationId, maxMemoryExchanges())) {
                conversationMemory.addExchange(memoryKey,
                        exchange.question(), exchange.answer());
            }
        } catch (RuntimeException e) {
            log.debug("恢复历史上下文失败: {}", e.getMessage());
        }
    }

    private int maxMemoryExchanges() {
        Integer configured = properties.getMemory().getMaxExchanges();
        return configured == null || configured <= 0 ? 6 : configured;
    }

    private String fallbackMarkdown(QueryTable table, int rowCount, boolean truncated) {
        StringBuilder answer = new StringBuilder();
        if (table == null || table.getRows().isEmpty()) {
            answer.append("没有查询到符合条件的数据。");
            return answer.toString();
        }
        answer.append("共查询到 ").append(rowCount).append(" 行数据。\n\n");
        int limit = Math.min(table.getRows().size(), 50);
        List<String> columns = table.getColumns();
        answer.append('|');
        for (String column : columns) {
            answer.append(' ').append(escapeCell(column)).append(" |");
        }
        answer.append('\n').append('|');
        for (int i = 0; i < columns.size(); i++) {
            answer.append(" --- |");
        }
        answer.append('\n');
        for (int i = 0; i < limit; i++) {
            List<String> row = table.getRows().get(i);
            answer.append('|');
            for (String value : row) {
                answer.append(' ').append(escapeCell(value)).append(" |");
            }
            answer.append('\n');
        }
        if (table.getRows().size() > limit) {
            answer.append("\n（为便于阅读，以上展示前 ").append(limit)
                    .append(" 行，完整数据见数据明细表）");
        }
        if (truncated) {
            answer.append("\n\n结果已按最大行数限制截断。");
        }
        return answer.toString();
    }

    private String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", "<br>");
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }
}
