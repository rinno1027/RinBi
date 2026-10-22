package org.rin.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据源 schema 的展示名映射：用表/字段注释替代真实名称，避免模型回答泄漏库表结构。
 *
 * <p>映射优先级：表名 + 字段名精确匹配 → 字段名匹配 → 原始列名。
 * 字段没有注释，或同一字段名在不同表中注释冲突时，回退到原始列名，保证展示语义不丢失。</p>
 */
public final class SchemaMetadata {

    private static final SchemaMetadata EMPTY = new SchemaMetadata(
            Collections.emptyMap(), Collections.emptyMap(),
            Collections.emptyMap(), Collections.emptySet());

    /**
     * key 为小写表名，value 为表注释。
     */
    private final Map<String, String> tableComments;

    /**
     * key 为小写“表名.字段名”，value 为字段注释。
     */
    private final Map<String, String> qualifiedColumnComments;

    /**
     * key 为小写字段名，value 为字段注释。
     */
    private final Map<String, String> columnComments;

    /**
     * 同名但注释不同的字段，不能只按字段名映射，避免张冠李戴。
     */
    private final Set<String> ambiguousColumns;

    public SchemaMetadata(Map<String, String> tableComments,
                          Map<String, String> qualifiedColumnComments,
                          Map<String, String> columnComments,
                          Set<String> ambiguousColumns) {
        this.tableComments = tableComments == null
                ? Collections.emptyMap() : tableComments;
        this.qualifiedColumnComments = qualifiedColumnComments == null
                ? Collections.emptyMap() : qualifiedColumnComments;
        this.columnComments = columnComments == null
                ? Collections.emptyMap() : columnComments;
        this.ambiguousColumns = ambiguousColumns == null
                ? Collections.emptySet() : ambiguousColumns;
    }

    public static SchemaMetadata empty() {
        return EMPTY;
    }

    public String tableComment(String table) {
        return table == null ? null : tableComments.get(key(table));
    }

    /**
     * 返回字段注释；先按表名 + 字段名匹配，再按字段名匹配。
     */
    public String columnComment(String table, String column) {
        if (column == null) {
            return null;
        }
        String columnKey = key(column);
        if (table != null) {
            String qualified = qualifiedColumnComments.get(key(table) + "." + columnKey);
            if (qualified != null) {
                return qualified;
            }
        }
        if (ambiguousColumns.contains(columnKey)) {
            return null;
        }
        return columnComments.get(columnKey);
    }

    /**
     * 把查询结果列名转换为面向用户的业务名称：优先字段注释，其次原始列名。
     * 转换后仍保持列名唯一，便于前端表格和图表按列名取值。
     */
    public List<String> displayColumns(List<ColumnRef> refs, List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> display = new ArrayList<>(labels.size());
        Set<String> used = new LinkedHashSet<>();
        for (int i = 0; i < labels.size(); i++) {
            String original = labels.get(i);
            ColumnRef ref = refs != null && i < refs.size() ? refs.get(i) : null;
            String comment = null;
            if (ref != null) {
                comment = columnComment(ref.getTable(), ref.getColumn());
                if (comment == null) {
                    comment = columnComment(null, ref.getColumn());
                }
            }
            if (comment == null) {
                comment = columnComment(null, original);
            }
            String label = hasText(comment) ? comment.trim() : original;
            display.add(unique(label, used));
        }
        return display;
    }

    /**
     * 真实表名/字段名 → 展示名。用于对模型答案做兜底替换：
     * 只包含有注释且无歧义的名称，避免误伤正常文本。
     */
    public Map<String, String> identifierLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        tableComments.forEach((name, comment) -> labels.putIfAbsent(name, comment));
        // “表名.字段名”本身已经限定到唯一字段，即使短字段名有歧义也可以安全替换。
        qualifiedColumnComments.forEach((qualified, comment) -> labels.putIfAbsent(qualified, comment));
        columnComments.forEach((name, comment) -> {
            if (!ambiguousColumns.contains(name)) {
                labels.putIfAbsent(name, comment);
            }
        });
        return labels;
    }

    private String unique(String label, Set<String> used) {
        String base = hasText(label) ? label.trim() : "column";
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
