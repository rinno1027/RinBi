package org.rin.db;

import org.rin.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读取数据库元数据，生成给模型使用的精简 schema 文本，并按配置缓存。
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);
    private static final Set<String> SYSTEM_SCHEMAS = new HashSet<>(Arrays.asList(
            "INFORMATION_SCHEMA", "PG_CATALOG", "SYS", "SYSTEM",
            "MYSQL", "PERFORMANCE_SCHEMA", "SQLITE_MASTER"
    ));

    @Autowired
    private DatabaseRegistry databaseRegistry;

    @Autowired
    private AgentProperties properties;

    private final Map<String, CachedSchema> cache = new ConcurrentHashMap<>();

    /**
     * 返回当前用户某个数据源的 schema 文本；读取失败时抛出 SqlExecutionException。
     */
    public String getSchema(Long userId, String databaseKey) {
        return snapshot(userId, databaseKey).text;
    }

    /**
     * 返回当前用户某个数据源的展示名映射（表/字段注释）。
     * 与 schema 文本共用缓存，避免一次对话重复读取元数据。
     */
    public SchemaMetadata getMetadata(Long userId, String databaseKey) {
        return snapshot(userId, databaseKey).metadata;
    }

    private CachedSchema snapshot(Long userId, String databaseKey) {
        DataSourceDefinition definition = databaseRegistry.requireOwned(userId, databaseKey);
        String cacheKey = cacheKey(userId, definition.getKey());
        long now = System.currentTimeMillis();
        CachedSchema cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt > now) {
            return cached;
        }
        CachedSchema loaded = loadSchema(definition);
        int ttl = properties.getSql().getSchemaCacheSeconds() == null
                ? 300 : properties.getSql().getSchemaCacheSeconds();
        if (ttl <= 0) {
            return loaded;
        }
        CachedSchema fresh = new CachedSchema(loaded.text, loaded.metadata,
                now + ttl * 1000L);
        cache.put(cacheKey, fresh);
        return fresh;
    }

    /**
     * 返回当前用户所有已启用数据源的 schema，用于让模型自行选择数据源。
     */
    public String getAllSchemas(Long userId) {
        StringBuilder builder = new StringBuilder();
        for (DataSourceDefinition definition : databaseRegistry.listForUser(userId)) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("=== 数据库 key: ").append(definition.getKey()).append(" ===\n");
            try {
                builder.append(getSchema(userId, definition.getKey()));
            } catch (RuntimeException e) {
                builder.append("（该数据库 schema 读取失败: ").append(e.getMessage()).append("）");
            }
        }
        return limit(builder.toString(), maxSchemaChars());
    }

    public void invalidate(Long userId, String databaseKey) {
        if (databaseKey == null) {
            return;
        }
        cache.remove(cacheKey(userId, databaseKey));
    }

    public void invalidateAll() {
        cache.clear();
    }

    private String cacheKey(Long userId, String databaseKey) {
        return (userId == null ? "anonymous" : userId) + ":" + databaseKey;
    }

    private CachedSchema loadSchema(DataSourceDefinition database) {
        String databaseKey = database.getKey();
        DataSource dataSource = databaseRegistry.getDataSource(database);
        int maxChars = maxSchemaChars();
        StringBuilder out = new StringBuilder();
        out.append("数据库: ").append(database.getName() == null ? databaseKey : database.getName())
                .append("（key=").append(databaseKey)
                .append(", dialect=").append(database.getDialect()).append("）\n");
        if (StringUtils.hasText(database.getDescription())) {
            out.append("说明: ").append(database.getDescription()).append("\n");
        }

        Map<String, String> tableComments = new LinkedHashMap<>();
        Map<String, String> qualifiedColumnComments = new LinkedHashMap<>();
        Map<String, String> columnComments = new LinkedHashMap<>();
        Set<String> ambiguousColumns = new HashSet<>();

        try (Connection connection = dataSource.getConnection()) {
            setReadOnly(connection);
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = safeCatalog(connection);
            List<TableInfo> tables = loadTables(metaData, catalog);
            if (tables.isEmpty()) {
                out.append("（未发现可访问的表）");
            }
            for (TableInfo table : tables) {
                String block = describeTable(metaData, catalog, table,
                        tableComments, qualifiedColumnComments, columnComments, ambiguousColumns);
                if (out.length() + block.length() > maxChars) {
                    out.append("\n（schema 超出 ").append(maxChars)
                            .append(" 字符限制，后续表已省略）");
                    break;
                }
                out.append(block);
            }
            SchemaMetadata metadata = new SchemaMetadata(tableComments,
                    qualifiedColumnComments, columnComments, ambiguousColumns);
            return new CachedSchema(out.toString(), metadata, 0L);
        } catch (SQLException e) {
            throw new SqlExecutionException("读取数据库 schema 失败（" + databaseKey + "）: "
                    + e.getMessage(), e);
        }
    }

    private List<TableInfo> loadTables(DatabaseMetaData metaData, String catalog) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        try (ResultSet resultSet = metaData.getTables(catalog, null, "%",
                new String[]{"TABLE", "VIEW"})) {
            while (resultSet.next()) {
                String schema = resultSet.getString("TABLE_SCHEM");
                String name = resultSet.getString("TABLE_NAME");
                if (!StringUtils.hasText(name) || isSystemSchema(schema)) {
                    continue;
                }
                String type = resultSet.getString("TABLE_TYPE");
                String remarks = resultSet.getString("REMARKS");
                tables.add(new TableInfo(schema, name, type, remarks));
            }
        }
        // 元数据返回顺序不稳定，排序保证提示词可复现。
        Collections.sort(tables, (a, b) -> {
            int schemaCompare = compareNullable(a.schema, b.schema);
            return schemaCompare != 0 ? schemaCompare : compareNullable(a.name, b.name);
        });
        return tables;
    }

    private String describeTable(DatabaseMetaData metaData, String catalog, TableInfo table,
                                 Map<String, String> tableComments,
                                 Map<String, String> qualifiedColumnComments,
                                 Map<String, String> columnComments,
                                 Set<String> ambiguousColumns) throws SQLException {
        StringBuilder out = new StringBuilder();
        out.append("\n表: ").append(table.name);
        if (StringUtils.hasText(table.schema)) {
            out.append("（schema=").append(table.schema).append("）");
        }
        if (StringUtils.hasText(table.type)) {
            out.append(" [").append(table.type).append("]");
        }
        String remarks = oneLine(table.remarks);
        if (StringUtils.hasText(remarks)) {
            out.append(" — ").append(remarks);
            tableComments.putIfAbsent(table.name.toLowerCase(Locale.ROOT), remarks);
        }
        out.append("\n  字段: ");

        List<String> columns = new ArrayList<>();
        try (ResultSet resultSet = metaData.getColumns(catalog, table.schema, table.name, "%")) {
            while (resultSet.next()) {
                String columnName = resultSet.getString("COLUMN_NAME");
                if (!StringUtils.hasText(columnName)) {
                    continue;
                }
                String typeName = resultSet.getString("TYPE_NAME");
                int size = resultSet.getInt("COLUMN_SIZE");
                boolean nullable = resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                StringBuilder column = new StringBuilder(columnName);
                column.append(' ').append(typeName == null ? "" : typeName);
                if (size > 0 && supportsSize(typeName)) {
                    column.append('(').append(size).append(')');
                }
                if (!nullable) {
                    column.append(" NOT NULL");
                }
                String columnRemarks = oneLine(resultSet.getString("REMARKS"));
                if (StringUtils.hasText(columnRemarks)) {
                    column.append(" // ").append(columnRemarks);
                    collectColumnComment(table.name, columnName, columnRemarks,
                            qualifiedColumnComments, columnComments, ambiguousColumns);
                }
                columns.add(column.toString());
            }
        }
        out.append(columns.isEmpty() ? "（无可用字段）" : String.join(", ", columns));
        return out.toString();
    }

    /**
     * 记录字段注释。同名但注释不同的字段会被标记为歧义，只允许按表名 + 字段名精确匹配。
     */
    private void collectColumnComment(String tableName, String columnName, String comment,
                                      Map<String, String> qualifiedColumnComments,
                                      Map<String, String> columnComments,
                                      Set<String> ambiguousColumns) {
        String tableKey = tableName.toLowerCase(Locale.ROOT);
        String columnKey = columnName.toLowerCase(Locale.ROOT);
        qualifiedColumnComments.putIfAbsent(tableKey + "." + columnKey, comment);
        String previous = columnComments.putIfAbsent(columnKey, comment);
        if (previous != null && !previous.equals(comment)) {
            ambiguousColumns.add(columnKey);
        }
    }

    private boolean supportsSize(String typeName) {
        if (typeName == null) {
            return false;
        }
        String type = typeName.toUpperCase(Locale.ROOT);
        return type.contains("CHAR") || type.contains("VARCHAR") || type.contains("DECIMAL")
                || type.contains("NUMERIC") || type.contains("BINARY");
    }

    private boolean isSystemSchema(String schema) {
        return schema != null && SYSTEM_SCHEMAS.contains(schema.toUpperCase(Locale.ROOT));
    }

    private String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    private void setReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException e) {
            log.debug("设置只读连接失败（读取 schema 阶段）: {}", e.getMessage());
        }
    }

    private int maxSchemaChars() {
        Integer value = properties.getSql().getMaxSchemaChars();
        return value == null || value < 1000 ? 30000 : value;
    }

    private String limit(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n（schema 已截断）";
    }

    private String oneLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() > 160 ? value.substring(0, 160) + "..." : value;
    }

    private int compareNullable(String left, String right) {
        if (left == null) {
            return right == null ? 0 : -1;
        }
        return right == null ? 1 : left.compareTo(right);
    }

    private static final class TableInfo {
        private final String schema;
        private final String name;
        private final String type;
        private final String remarks;

        private TableInfo(String schema, String name, String type, String remarks) {
            this.schema = schema;
            this.name = name;
            this.type = type;
            this.remarks = remarks;
        }
    }

    private static final class CachedSchema {
        private final String text;
        private final SchemaMetadata metadata;
        private final long expiresAt;

        private CachedSchema(String text, SchemaMetadata metadata, long expiresAt) {
            this.text = text;
            this.metadata = metadata;
            this.expiresAt = expiresAt;
        }
    }
}
