package org.rin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.PostConstruct;
import org.rin.agent.dto.QueryPlan;
import org.rin.agent.dto.QueryTable;
import org.rin.agent.dto.ReasoningStreamResult;
import org.rin.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 与 LLM 交互：第一阶段生成查询计划，第二阶段把查询结果整理成 Markdown。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    /**
     * 由 {@link org.rin.config.LlmConfig#openAiStreamingChatModel(AgentProperties)} 提供的
     * 流式模型（标注了 @Primary）。LangChain4j 1.x 已移除阻塞式 {@code generate(...)}，
     * 因此这里统一走流式接口，需要完整文本时由 {@link #call(String, String)} 汇总。
     */
    @Autowired
    private StreamingChatModel chatModel;

    /**
     * 结果整理阶段使用支持 reasoning_content 的流式客户端：
     * 思考过程实时回调，最终答案等流结束后整段返回。
     */
    @Autowired
    private ReasoningStreamClient reasoningStreamClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentProperties properties;

    @Value("classpath:prompts/plan-system.txt")
    private Resource planPrompt;

    @Value("classpath:prompts/format-system.txt")
    private Resource formatPrompt;

    private String planSystemPrompt;
    private String formatSystemPrompt;

    /**
     * Bean 初始化时一次性读取提示词文件，避免每次请求都做磁盘 IO。
     */
    @PostConstruct
    public void initPrompts() {
        this.planSystemPrompt = readPrompt(planPrompt, "prompts/plan-system.txt");
        this.formatSystemPrompt = readPrompt(formatPrompt, "prompts/format-system.txt");
    }

    private String readPrompt(Resource resource, String path) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词失败: " + path, e);
        }
    }

    /**
     * 生成查询计划。模型输出不是合法 JSON 时会自动要求其修复一次。
     */
    public QueryPlan plan(String question, String databaseKey, String schemaText, String historyText) {
        String userPrompt = buildPlanUserPrompt(question, databaseKey, schemaText, historyText);
        String raw = call(planSystemPrompt, userPrompt);
        try {
            return parsePlan(raw);
        } catch (RuntimeException firstFailure) {
            log.warn("查询计划 JSON 解析失败，尝试让模型修复一次: {}", firstFailure.getMessage());
            String repairPrompt = userPrompt + "\n\n上一次输出无法解析为 JSON：\n"
                    + abbreviate(raw, 3000)
                    + "\n请只重新输出严格 JSON，不要包含 Markdown 代码块或其他文字。";
            String repaired = call(planSystemPrompt, repairPrompt);
            try {
                return parsePlan(repaired);
            } catch (RuntimeException secondFailure) {
                throw new IllegalStateException("模型返回的查询计划不是合法 JSON: "
                        + secondFailure.getMessage(), secondFailure);
            }
        }
    }

    /**
     * 用模型把结构化结果整理为 Markdown。失败时由调用方回退到本地表格。
     */
    public String format(String question, QueryTable table, int totalRows,
                         boolean truncated, boolean tooLarge) {
        String userPrompt = buildFormatUserPrompt(question, table, totalRows, truncated, tooLarge);
        return call(formatSystemPrompt, userPrompt).trim();
    }

    /**
     * 流式整理结果：思考过程通过 {@code onReasoning} 实时回调，答案通过 {@code onToken}
     * 实时回调，最终完整文本在流结束后返回。这样前端既能展示可折叠的思考过程，
     * 也能边生成边渲染答案。
     *
     * @param onReasoning 思考分片回调，可能被 IO 线程调用；回调中的异常不会中断生成
     * @param onToken     答案分片回调，可能被 IO 线程调用；回调中的异常不会中断生成
     * @return 完整思考文本与最终 Markdown 答案
     */
    public ReasoningStreamResult formatWithReasoning(String question, QueryTable table,
                                                     int totalRows, boolean truncated,
                                                     boolean tooLarge,
                                                     Consumer<String> onReasoning,
                                                     Consumer<String> onToken) {
        String userPrompt = buildFormatUserPrompt(question, table, totalRows, truncated, tooLarge);
        try {
            return reasoningStreamClient.stream(formatSystemPrompt, userPrompt, onReasoning, onToken);
        } catch (RuntimeException e) {
            throw new IllegalStateException("调用推理模型失败: " + safeMessage(e), e);
        }
    }

    private String buildFormatUserPrompt(String question, QueryTable table, int totalRows,
                                         boolean truncated, boolean tooLarge) {
        String data = buildDataText(table, totalRows, truncated, tooLarge);
        // 不把真实 SQL 交给结果整理模型：SQL 中的表名/字段名会被模型直接写进答案。
        return "用户问题：\n" + question
                + "\n\n查询结果总行数：" + totalRows
                + "\n结果是否因最大行数被截断：" + (truncated ? "是" : "否")
                + "\n结果是否超限：" + (tooLarge ? "是" : "否")
                + "\n\n" + data
                + "\n\n请严格按照系统要求用中文输出结论和 Markdown 表格。";
    }

    /**
     * 需要完整文本的一次性调用（生成查询计划、修复 JSON、格式化结果回退路径）。
     *
     * <p>内部同样走流式接口：分片累积到缓冲区，流结束后返回整段文本，
     * 从而保证“所有模型调用都是流式的”这一约束。</p>
     */
    private String call(String systemPrompt, String userPrompt) {
        StringBuilder buffer = new StringBuilder();
        streamOnce(systemPrompt, userPrompt, buffer::append);
        String text = buffer.toString();
        if (text.trim().isEmpty()) {
            throw new IllegalStateException("模型返回为空");
        }
        log.debug("LLM 响应（{} 字符）", text.length());
        return text;
    }

    /**
     * 发起一次流式对话并阻塞到流结束，分片通过 {@code onToken} 回传。
     */
    private void streamOnce(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
                .build();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            chatModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (partialResponse != null && !partialResponse.isEmpty()) {
                        onToken.accept(partialResponse);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    completed.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    failure.compareAndSet(null, error);
                    completed.countDown();
                }
            });
            // 比模型自身超时多留一点余量，正常情况下由模型先触发 onComplete/onError。
            if (!completed.await(resolveStreamTimeoutSeconds() + 30L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("模型响应超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用模型被中断", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("调用模型失败: " + safeMessage(e), e);
        }
        Throwable error = failure.get();
        if (error != null) {
            throw new IllegalStateException("调用模型失败: " + safeMessage(error), error);
        }
    }

    private int resolveStreamTimeoutSeconds() {
        Integer timeout = properties.getLlm().getTimeoutSeconds();
        return timeout == null || timeout <= 0 ? 300 : timeout;
    }

    private QueryPlan parsePlan(String raw) {
        String json = extractJson(raw);
        try {
            QueryPlan plan = objectMapper.readValue(json, QueryPlan.class);
            if (plan == null) {
                throw new IllegalArgumentException("查询计划为空");
            }
            if (plan.getFeasible() == null) {
                plan.setFeasible(Boolean.FALSE);
            }
            if (plan.getSql() != null) {
                plan.setSql(stripSqlFence(plan.getSql()).trim());
            }
            return plan;
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + safeMessage(e), e);
        }
    }

    private String extractJson(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("模型输出为空");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalArgumentException("未找到 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    private String stripSqlFence(String sql) {
        String value = sql.trim();
        if (value.startsWith("```")) {
            int firstLineEnd = value.indexOf('\n');
            if (firstLineEnd > 0) {
                value = value.substring(firstLineEnd + 1);
            }
            int fence = value.lastIndexOf("```");
            if (fence >= 0) {
                value = value.substring(0, fence);
            }
        }
        return value.trim();
    }

    private String buildPlanUserPrompt(String question, String databaseKey,
                                       String schemaText, String historyText) {
        StringBuilder prompt = new StringBuilder();
        if (historyText != null && !historyText.trim().isEmpty()) {
            prompt.append("最近会话历史：\n").append(historyText).append("\n\n");
        }
        prompt.append("可用数据库及表结构：\n").append(schemaText).append("\n\n");
        if (databaseKey != null && !databaseKey.trim().isEmpty()) {
            prompt.append("用户指定数据库 key：").append(databaseKey)
                    .append("（database 字段必须返回该 key）\n");
        } else {
            prompt.append("用户未指定数据库，请根据问题选择最合适的一个。\n");
        }
        prompt.append("\n用户问题：\n").append(question);
        return prompt.toString();
    }

    private String buildDataText(QueryTable table, int totalRows, boolean truncated, boolean tooLarge) {
        int rowLimit = properties.getSql().getFormatRowLimit() == null
                ? 100 : Math.max(1, properties.getSql().getFormatRowLimit());
        int maxChars = properties.getSql().getFormatMaxChars() == null
                ? 18000 : Math.max(1000, properties.getSql().getFormatMaxChars());
        List<String> columns = table == null ? new ArrayList<>() : table.getColumns();
        List<List<String>> rows = table == null ? new ArrayList<>() : table.getRows();

        StringBuilder data = new StringBuilder();
        data.append("查询结果（列名已经是业务显示名，必须原样使用，禁止替换为数据库字段名）：\n");
        data.append(markdownRow(columns)).append('\n');
        data.append(markdownSeparator(columns.size())).append('\n');
        int provided = 0;
        for (List<String> row : rows) {
            if (provided >= rowLimit || data.length() >= maxChars) {
                break;
            }
            data.append(markdownRow(row)).append('\n');
            provided++;
        }
        if (provided < totalRows) {
            data.append("\n（为便于阅读，以下仅提供前 ").append(provided)
                    .append(" 行；完整数据共 ").append(totalRows).append(" 行）");
        }
        if (truncated) {
            data.append("\n（注意：数据库结果已按最大行数限制截断，未读取全部数据）");
        }
        if (tooLarge) {
            data.append("\n（注意：结果超过字符上限，数据不可用）");
        }
        if (data.length() > maxChars) {
            return data.substring(0, maxChars) + "\n（数据文本已截断）";
        }
        return data.toString();
    }

    private String markdownRow(List<String> values) {
        StringBuilder row = new StringBuilder("|");
        if (values == null || values.isEmpty()) {
            return "| |";
        }
        for (String value : values) {
            row.append(' ').append(escapeCell(value)).append(" |");
        }
        return row.toString();
    }

    private String markdownSeparator(int columns) {
        StringBuilder separator = new StringBuilder("|");
        for (int i = 0; i < Math.max(1, columns); i++) {
            separator.append(" --- |");
        }
        return separator.toString();
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

    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }
}
