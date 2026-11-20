package org.rin.rule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 注入查询规划提示词的规则 JSON 结构。
 *
 * <p>只包含模型理解统计口径所需的业务信息，不携带创建人、审计时间等内部字段。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatRulePrompt {

    private String ruleCode;
    private String ruleName;
    private String ruleDescription;
    private String formula;
    private String businessRule;
    private String remark;
    private String versionNo;
    private List<InputField> inputFields;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InputField {

        private String sourceTable;
        private String fieldName;
        private String fieldDescription;
    }
}
