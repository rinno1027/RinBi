package org.rin.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能报表导入模板，对应元数据库 rinbidb.report_template。
 *
 * <p>模板文件与生成的报表文件一样保存在服务器本地目录，数据库只保存元信息与归属；
 * 一个会话（{@code conversation_id}）只允许导入一个模板，用户可以在该会话中
 * 通过自然语言多轮调整取数口径。{@code plan_json} 保存最近一次可用的填充计划，
 * 供后续调整时作为上下文。</p>
 */
@Data
@TableName("report_template")
public class ReportTemplateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户 sys_user.id。
     */
    private Long userId;

    /**
     * 关联的会话业务 ID（唯一）。
     */
    private String conversationId;

    /**
     * 用户上传时的模板文件名，用于展示与下载。
     */
    private String templateName;

    /**
     * 存储目录内的文件名（UUID + 扩展名）。
     */
    private String storedName;

    private String contentType;

    private Long fileSize;

    /**
     * 模板结构摘要：工作表数量与已用区域，便于前端与会话标题展示。
     */
    private String sheetSummary;

    /**
     * 最近一次成功生效的报表取数计划（ReportPlan JSON）。
     */
    private String planJson;

    /**
     * READY / FAILED。
     */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
