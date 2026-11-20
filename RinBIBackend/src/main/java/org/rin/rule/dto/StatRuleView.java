package org.rin.rule.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计规则列表/详情视图。
 *
 * <p>{@code canEdit} 由后端按当前用户是否为 ADMIN 计算，前端只用于展示按钮状态，
 * 最终权限仍由服务端校验。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatRuleView {

    private Long id;
    private String dataSourceKey;
    private String dataSourceName;
    private String versionNo;
    private String ruleCode;
    private String ruleName;
    private String ruleDescription;
    private String formula;
    private String businessRule;
    private String remark;
    private Long createdBy;
    private String createdByName;
    private Long updatedBy;
    private String updatedByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean canEdit;
    private List<StatRuleInputFieldView> inputFields;
}
