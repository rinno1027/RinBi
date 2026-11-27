package org.rin.report.dto;

import lombok.Data;

import java.util.List;

/**
 * 智能报表会话中的一次调整请求：基于已上传的模板生成或修改报表。
 *
 * <p>会话中只允许存在一个模板，因此 {@code templateId} 只是可选的显式指定；
 * 缺省时按 {@code conversationId} 查找该会话的模板。</p>
 */
@Data
public class ReportChatRequest {

    /**
     * 报表会话业务 ID，必填；由上传模板时创建。
     */
    private String conversationId;

    /**
     * 模板 ID，可留空（默认取会话绑定的模板）。
     */
    private Long templateId;

    /**
     * 本轮需求或调整说明，例如“按月份重排，并增加同比列”。
     */
    private String question;

    /**
     * 指定数据源 key；为空时由模型根据 schema 自动选择。
     */
    private String database;

    /**
     * 本轮勾选的统计规则 ID；只有显式指定 database 时才允许使用。
     */
    private List<Long> ruleIds;
}
