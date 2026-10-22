package org.rin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rin.agent.dto.ChartSpec;
import org.rin.agent.dto.QueryTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析并校验模型答案末尾的 chart 代码块。
 *
 * <p>模型只输出图表描述（类型、列名、标题、单位），这里负责：
 * <ul>
 *   <li>把一个或多个 chart 代码块从最终 Markdown 中剥离，避免前端展示原始 JSON；</li>
 *   <li>校验类型、列名与数值列，非法图表直接丢弃但保留文字答案；</li>
 *   <li>把列名归一为查询结果中的真实列名，降低大小写/空格差异带来的影响。</li>
 * </ul>
 */
@Component
public class ChartSpecParser {

    private static final Logger log = LoggerFactory.getLogger(ChartSpecParser.class);

    /**
     * 匹配围栏标记为 chart 的代码块，未闭合时匹配到文本末尾（流式输出阶段可能尚未闭合）。
     * 不匹配 charts 之类的其他围栏标记。
     */
    private static final Pattern CHART_BLOCK = Pattern.compile(
            "(?is)```chart(?![\\w-])[ \\t]*\\r?\\n?\\s*(.*?)(?:```|$)");

    private static final Set<String> SUPPORTED_TYPES =
            Collections.unmodifiableSet(new LinkedHashSet<>(List.of("bar", "line", "pie", "scatter")));

    private static final int MAX_CHARTS = 4;
    private static final int MAX_Y_FIELDS = 4;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int MAX_UNIT_LENGTH = 20;
    private static final int MIN_ROWS = 2;
    private static final int MIN_NUMERIC_VALUES = 2;

    private final ObjectMapper objectMapper;

    public ChartSpecParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从答案中提取图表描述，并返回剥离 chart 代码块后的 Markdown。
     */
    public Result extract(String markdown, QueryTable table) {
        String source = markdown == null ? "" : markdown;
        Matcher matcher = CHART_BLOCK.matcher(source);
        if (!matcher.find()) {
            return new Result(source, new ArrayList<>());
        }

        List<ChartSpec> charts = new ArrayList<>();
        StringBuffer cleaned = new StringBuffer();
        do {
            String json = matcher.group(1) == null ? "" : matcher.group(1).trim();
            // 一个代码块里允许放单个对象；同时兼容数组和 {"charts":[...]} 包装，
            // 避免模型合并输出时整块图表被丢弃。
            List<ChartSpec> candidates = parseAll(json);
            for (ChartSpec candidate : candidates) {
                if (charts.size() >= MAX_CHARTS) {
                    break;
                }
                ChartSpec validated = validate(candidate, table);
                if (validated != null) {
                    charts.add(validated);
                }
            }
            matcher.appendReplacement(cleaned, "");
        } while (matcher.find());
        matcher.appendTail(cleaned);

        String content = cleaned.toString()
                .replaceAll("(?m)^[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (content.isEmpty() && !charts.isEmpty()) {
            content = "已根据查询结果生成图表，详见下方。";
        }
        return new Result(content, charts);
    }

    /**
     * 解析一个 chart 代码块，支持单个对象、对象数组和 {"charts":[...]} 三种写法。
     */
    private List<ChartSpec> parseAll(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isNull()) {
                return Collections.emptyList();
            }
            if (node.isArray()) {
                return parseNodes(node);
            }
            if (node.isObject() && node.has("charts") && node.get("charts").isArray()) {
                return parseNodes(node.get("charts"));
            }
            if (!node.isObject()) {
                return Collections.emptyList();
            }
            ChartSpec spec = objectMapper.treeToValue(node, ChartSpec.class);
            return spec == null ? Collections.emptyList() : List.of(spec);
        } catch (Exception e) {
            log.warn("忽略无法解析的 chart 代码块: {}", abbreviate(json, 200));
            return Collections.emptyList();
        }
    }

    private List<ChartSpec> parseNodes(JsonNode nodes) {
        List<ChartSpec> specs = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node == null || !node.isObject()) {
                continue;
            }
            try {
                ChartSpec spec = objectMapper.treeToValue(node, ChartSpec.class);
                if (spec != null) {
                    specs.add(spec);
                }
            } catch (Exception e) {
                log.warn("忽略无法解析的图表定义: {}", abbreviate(node.toString(), 200));
            }
        }
        return specs;
    }

    private ChartSpec validate(ChartSpec spec, QueryTable table) {
        if (table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return null;
        }
        List<List<String>> rows = table.getRows() == null
                ? Collections.emptyList() : table.getRows();
        if (rows.size() < MIN_ROWS) {
            return null;
        }

        String type = normalizeType(spec.getType());
        if (type == null) {
            log.debug("忽略不支持的图表类型: {}", spec.getType());
            return null;
        }

        String xField = resolveColumn(spec.getXField(), table.getColumns());
        if (xField == null) {
            log.debug("忽略图表：横轴列不存在 {}", spec.getXField());
            return null;
        }

        List<String> yFields = new ArrayList<>();
        if (spec.getYFields() != null) {
            for (String candidate : spec.getYFields()) {
                if (yFields.size() >= MAX_Y_FIELDS) {
                    break;
                }
                String yField = resolveColumn(candidate, table.getColumns());
                if (yField == null) {
                    log.debug("忽略图表：数值列不存在 {}", candidate);
                    return null;
                }
                int index = table.getColumns().indexOf(yField);
                if (!isNumericColumn(rows, index)) {
                    log.debug("忽略图表：列不是数值列 {}", yField);
                    return null;
                }
                if (!yFields.contains(yField)) {
                    yFields.add(yField);
                }
            }
        }
        if (yFields.isEmpty()) {
            return null;
        }
        if ("pie".equals(type) && yFields.size() != 1) {
            log.debug("忽略图表：饼图只能有一个数值列");
            return null;
        }

        String title = StringUtils.hasText(spec.getTitle())
                ? abbreviate(spec.getTitle().trim(), MAX_TITLE_LENGTH)
                : xField + " 图表";
        String unit = StringUtils.hasText(spec.getUnit())
                ? abbreviate(spec.getUnit().trim(), MAX_UNIT_LENGTH)
                : null;

        return ChartSpec.builder()
                .type(type)
                .title(title)
                .xField(xField)
                .yFields(yFields)
                .unit(unit)
                .build();
    }


    private String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_TYPES.contains(normalized) ? normalized : null;
    }

    /**
     * 优先精确匹配，其次忽略大小写与首尾空格匹配；返回真实列名。
     */
    private String resolveColumn(String field, List<String> columns) {
        if (!StringUtils.hasText(field) || columns == null) {
            return null;
        }
        String candidate = field.trim();
        for (String column : columns) {
            if (column != null && column.equals(candidate)) {
                return column;
            }
        }
        for (String column : columns) {
            if (column != null && column.trim().equalsIgnoreCase(candidate)) {
                return column;
            }
        }
        return null;
    }

    private boolean isNumericColumn(List<List<String>> rows, int columnIndex) {
        int numeric = 0;
        for (List<String> row : rows) {
            if (row == null || columnIndex < 0 || columnIndex >= row.size()) {
                continue;
            }
            if (parseNumber(row.get(columnIndex)) != null) {
                numeric++;
                if (numeric >= MIN_NUMERIC_VALUES) {
                    return true;
                }
            }
        }
        return false;
    }

    private BigDecimal parseNumber(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim()
                .replace(",", "")
                .replace("，", "");
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 解析结果：剥离图表块后的 Markdown + 通过校验的图表描述。
     */
    public static class Result {

        private final String markdown;
        private final List<ChartSpec> charts;

        public Result(String markdown, List<ChartSpec> charts) {
            this.markdown = markdown;
            this.charts = charts == null ? new ArrayList<>() : charts;
        }

        public String getMarkdown() {
            return markdown;
        }

        public List<ChartSpec> getCharts() {
            return charts;
        }
    }
}

