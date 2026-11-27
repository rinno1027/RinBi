package org.rin.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能报表生成文件记录，对应元数据库 rinbidb.report_file。
 *
 * <p>文件内容保存在 {@code agent.report.storage-dir}，数据库只保存元信息与归属，
 * 下载时必须按 user_id 校验，避免越权访问。</p>
 */
@Data
@TableName("report_file")
public class ReportFileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户 sys_user.id。
     */
    private Long userId;

    /**
     * 关联的会话业务 ID，可为空。
     */
    private String conversationId;

    /**
     * 生成该文件所用的模板 report_template.id，可为空。
     */
    private Long templateId;

    /**
     * 下载时展示的文件名。
     */
    private String fileName;

    /**
     * 存储目录内的文件名（UUID + 扩展名）。
     */
    private String storedName;

    private String contentType;

    private Long fileSize;

    /**
     * READY / FAILED。
     */
    private String status;

    private LocalDateTime createdAt;
}
