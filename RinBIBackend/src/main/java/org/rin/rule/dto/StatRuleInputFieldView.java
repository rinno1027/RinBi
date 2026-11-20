package org.rin.rule.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统计规则输入字段视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatRuleInputFieldView {

    private Long id;
    private String sourceTable;
    private String fieldName;
    private String fieldDescription;
    private Integer sortOrder;
}
