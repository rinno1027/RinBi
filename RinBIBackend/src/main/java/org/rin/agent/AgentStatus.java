package org.rin.agent;

/**
 * Agent 单次请求的处理状态。
 */
public enum AgentStatus {
    /** 查询成功，并已生成分析结果 */
    SUCCESS,
    /** 模型判断当前数据无法支持该问题 */
    NOT_FEASIBLE,
    /** 模型生成了非只读 SQL，被安全校验拦截 */
    UNSAFE_SQL,
    /** 查询结果超过字符上限，未返回数据 */
    OUTPUT_TOO_LARGE,
    /** 模型调用或数据库执行异常 */
    ERROR
}
