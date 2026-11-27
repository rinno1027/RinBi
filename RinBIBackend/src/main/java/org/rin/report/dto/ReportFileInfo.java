package org.rin.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 报表文件元信息，随聊天响应下发给前端，用于渲染下载链接。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportFileInfo {

    private Long fileId;

    private String fileName;

    /**
     * 下载地址，例如 /api/report/files/12。
     */
    private String downloadUrl;

    private Long fileSize;

    private Integer rowCount;

    private String summary;
}
