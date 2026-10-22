package org.rin.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 暴露给前端的数据库信息（不包含连接凭据）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseInfo {

    private String key;
    private String name;
    private String description;
    private String dialect;
}
