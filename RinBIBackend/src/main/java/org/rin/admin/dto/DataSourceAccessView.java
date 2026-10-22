package org.rin.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 数据源授权弹框数据：全部用户与当前已授权用户 ID。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceAccessView {

    private Long dataSourceId;

    private String dataSourceName;

    private List<AdminUserView> users;

    private List<Long> assignedUserIds;
}
