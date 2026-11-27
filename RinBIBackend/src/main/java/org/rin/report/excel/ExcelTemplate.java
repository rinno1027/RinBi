package org.rin.report.excel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析后的 Excel 模板结构，仅用于构造模型提示词，不参与实际写入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExcelTemplate {

    @Builder.Default
    private List<Sheet> sheets = new ArrayList<>();

    private int maxRow;
    private int maxColumn;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sheet {
        private String name;
        private int lastRow;
        private int lastColumn;
        @Builder.Default
        private List<Row> rows = new ArrayList<>();
        @Builder.Default
        private List<Merged> mergedRegions = new ArrayList<>();
        /**
         * 形如 A1 的占位符单元格，模板作者可用 {{字段}} 明确要求填充位置。
         */
        @Builder.Default
        private List<Placeholder> placeholders = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private int row;
        @Builder.Default
        private List<Cell> cells = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cell {
        /**
         * 1-based 列号。
         */
        private int col;
        private String address;
        /**
         * 单元格显示值：公式单元格为 POI 计算（或缓存）后的结果。
         */
        private String text;
        /**
         * 公式原文（统一带前导 =），非公式单元格为 null。
         */
        private String formula;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Merged {
        private String range;
        private int firstRow;
        private int firstColumn;
        private int lastRow;
        private int lastColumn;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Placeholder {
        private String name;
        private String sheet;
        private int row;
        private int col;
        private String address;
    }
}
