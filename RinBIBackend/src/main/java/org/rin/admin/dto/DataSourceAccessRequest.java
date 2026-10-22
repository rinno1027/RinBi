package org.rin.admin.dto;

import lombok.Data;

import java.util.List;

/**
 * 保存数据源可访问用户列表。
 */
@Data
public class DataSourceAccessRequest {

    /**
     * 最终勾选的用户 ID；空数组表示撤销该数据源的全部授权。
     */
    private List<Long> userIds;
}
