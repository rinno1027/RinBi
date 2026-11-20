package org.rin.rule.dto;

import lombok.Data;

/**
 * 统计规则的新建/修改请求。
 *
 * <p>服务端只信任这些业务字段；创建人、版本号、审计时间均由后端生成。</p>
 */
@Data
public class StatRuleRequest {

    private String dataSourceKey;
    private String ruleCode;
    private String ruleName;
    private String ruleDescription;
    private String formula;
    private String businessRule;
    private String remark;
    private java.util.List<StatRuleInputFieldRequest> inputFields;
}
