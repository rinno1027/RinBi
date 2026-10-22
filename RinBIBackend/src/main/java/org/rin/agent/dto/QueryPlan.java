package org.rin.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 模型第一阶段输出的结构化查询计划。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryPlan {

    private Boolean feasible;
    private String database;
    private String sql;
    private String explanation;
    private String reason;

    public boolean isFeasiblePlan() {
        return Boolean.TRUE.equals(feasible);
    }
}
