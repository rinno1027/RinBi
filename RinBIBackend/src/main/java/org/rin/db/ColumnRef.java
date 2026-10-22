package org.rin.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询结果中某一列的来源信息，用于把真实字段名映射为字段注释。
 *
 * <p>JDBC 对普通字段可以返回来源表与真实列名；对表达式、聚合列或别名列，
 * 这些信息可能为空，此时调用方会回退到查询结果中的列名。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnRef {

    /**
     * 查询结果中实际使用的列名（已保证同一结果内唯一）。
     */
    private String label;

    /**
     * 数据库中的真实列名；表达式或别名列可能为空。
     */
    private String column;

    /**
     * 字段来源表名；表达式、别名列或部分驱动可能为空。
     */
    private String table;
}
