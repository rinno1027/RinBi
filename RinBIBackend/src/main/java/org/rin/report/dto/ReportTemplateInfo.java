package org.rin.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能报表导入模板的元信息，随会话详情、上传响应下发前端。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportTemplateInfo {

    private Long templateId;

    /**
     * 该模板所属的会话业务 ID（一个会话只允许一个模板）。
     */
    private String conversationId;

    private String templateName;

    /**
     * 模板下载地址，例如 /api/report/templates/12。
     */
    private String downloadUrl;

    private Long fileSize;

    private String contentType;

    /**
     * 模板结构摘要，例如 "客户统计(20×6); 汇总(8×4)"。
     */
    private String sheetSummary;

    private LocalDateTime createdAt;
}
