package org.rin.rule.dto;

import lombok.Data;

/**
 * 统计规则依赖的输入字段。
 */
@Data
public class StatRuleInputFieldRequest {

    private String sourceTable;
    private String fieldName;
    private String fieldDescription;
}
