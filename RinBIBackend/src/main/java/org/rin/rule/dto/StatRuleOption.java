package org.rin.rule.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话页规则多选框的候选项。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatRuleOption {

    private Long id;
    private String ruleCode;
    private String ruleName;
    private String ruleDescription;
}
