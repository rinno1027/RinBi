package org.rin.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次只读查询的执行结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResult {

    private String database;
    private String sql;
    @Builder.Default
    private List<String> columns = new ArrayList<>();
    /**
     * 每一列的来源信息，用于把真实字段名映射为字段注释；仅后端内部使用。
     */
    @Builder.Default
    private List<ColumnRef> columnRefs = new ArrayList<>();
    @Builder.Default
    private List<List<String>> rows = new ArrayList<>();
    private int rowCount;
    /**
     * 是否因为 max-rows 被截断（仍有更多数据未读取）。
     */
    private boolean truncated;
    /**
     * 是否超过 max-result-chars，超限时不返回数据。
     */
    private boolean tooLarge;
    private int charCount;
    private long elapsedMs;
    private String message;
}
