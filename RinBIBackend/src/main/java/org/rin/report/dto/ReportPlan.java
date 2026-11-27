package org.rin.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型返回的 Excel 报表填充计划。
 *
 * <p>模型必须输出严格 JSON，坐标统一使用 Excel 的 1-based 行列号。
 * 后端不会直接信任该计划：SQL 会经过 {@code SqlGuard} 校验，单元格坐标与列名
 * 也会再次校验，越界或未知列会直接拒绝。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportPlan {

    private Boolean feasible;

    /**
     * 计划查询的数据源 key。
     */
    private String database;

    /**
     * 面向用户的报表说明。
     */
    private String summary;

    private String reason;

    /**
     * 取数 SQL；每条 SQL 用 alias 标识，供单元格/表格映射引用。
     */
    private List<SqlSpec> sqls = new ArrayList<>();

    /**
     * 单点填充，例如标题、统计月份、合计等。
     */
    private List<CellMapping> cells = new ArrayList<>();

    /**
     * 明细表格区域，按 SQL 结果逐行展开。
     */
    private List<TableMapping> tables = new ArrayList<>();

    public boolean isFeasiblePlan() {
        return Boolean.TRUE.equals(feasible);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SqlSpec {
        /**
         * SQL 别名，供 cells/tables 的 source 引用。
         */
        private String alias;
        private String sql;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CellMapping {
        private String sheet;
        /**
         * 1-based 行号。
         */
        private Integer row;
        /**
         * 1-based 列号。
         */
        private Integer col;
        private String source;
        /**
         * 结果列名；也兼容 1-based 列序号字符串。
         */
        private String column;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TableMapping {
        private String sheet;
        /**
         * 第一条数据行的 1-based 行号；模板中该行的样式会复制到后续数据行。
         */
        private Integer startRow;
        /**
         * 第一列的 1-based 列号。
         */
        private Integer startCol;
        private String source;
        /**
         * 需要写入的结果列；为空时按 SQL 结果列顺序全部写入。
         */
        private List<String> columns = new ArrayList<>();
        /**
         * 可选：表头行（1-based）。填写后会把结果列名写入该行。
         */
        private Integer headerRow;
        /**
         * 可选：最多写入行数；最终仍受 agent.report.max-fill-rows 限制。
         */
        private Integer maxRows;
    }
}
