package org.rin.report.excel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 已解析、已校验的填充数据，由服务层根据 SQL 结果生成，Excel 填充器只负责写入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportFillPlan {

    @Builder.Default
    private List<CellValue> cells = new ArrayList<>();

    @Builder.Default
    private List<TableValue> tables = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CellValue {
        private String sheet;
        /**
         * 1-based 行号。
         */
        private int row;
        /**
         * 1-based 列号。
         */
        private int col;
        private String value;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableValue {
        private String sheet;
        /**
         * 第一条数据行的 1-based 行号。
         */
        private int startRow;
        /**
         * 第一列的 1-based 列号。
         */
        private int startCol;
        /**
         * 写入表头行的列名；为空时不写表头。
         */
        private List<String> headers;
        private Integer headerRow;
        private List<List<String>> rows;
    }
}
