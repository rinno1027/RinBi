package org.rin.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rin.agent.AgentStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 响应。answer 为模型整理的 Markdown，table 为结构化数据，便于前端直接渲染表格。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String conversationId;

    private AgentStatus status;

    /**
     * 面向用户的 Markdown 文本；失败时也包含可读的说明。
     */
    private String answer;

    /**
     * 模型返回的完整思考过程；不支持推理的模型为空。
     */
    private String reasoning;

    /**
     * 不可行/被拦截/异常时的原因。
     */
    private String reason;

    private String database;

    private String databaseName;

    /**
     * 本次实际执行的 SQL。
     */
    private String sql;

    /**
     * 结构化查询结果；结果超限或未执行时为 null。
     */
    private QueryTable table;

    /**
     * 模型建议的图表描述；无合适图表时为空列表。
     * 图表数据不重复下发，前端从 {@link #table} 取值渲染。
     */
    @Builder.Default
    private List<ChartSpec> charts = new ArrayList<>();

    private Integer rowCount;

    /**
     * 是否因 max-rows 被截断。
     */
    private Boolean truncated;

    private Long elapsedMs;

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    private String timestamp;
}
