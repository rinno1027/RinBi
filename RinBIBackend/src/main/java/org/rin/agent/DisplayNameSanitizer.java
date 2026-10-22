package org.rin.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 把回答中残留的数据库表名/字段名替换为字段注释。
 *
 * <p>模型整理结果时理论上只会看到业务列名，但仍可能在洞察、标题或思考过程里
 * 写出真实字段名。这里用单词边界匹配做一次兜底，匹配不区分大小写，
 * 并让较长的名称优先匹配（例如先匹配 customer_id，再匹配 id）。</p>
 */
public final class DisplayNameSanitizer {

    private final Map<String, String> labels;
    private final Pattern pattern;

    public DisplayNameSanitizer(Map<String, String> labels) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (labels != null) {
            labels.forEach((name, label) -> {
                if (name == null || name.trim().isEmpty()
                        || label == null || label.trim().isEmpty()) {
                    return;
                }
                String key = name.trim().toLowerCase(Locale.ROOT);
                String value = label.trim();
                // 注释和字段名相同就没有替换的必要。
                if (!key.equals(value.toLowerCase(Locale.ROOT))) {
                    normalized.putIfAbsent(key, value);
                }
            });
        }
        this.labels = normalized;
        List<String> names = new ArrayList<>(normalized.keySet());
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        this.pattern = names.isEmpty() ? null : Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])("
                        + names.stream().map(Pattern::quote).collect(Collectors.joining("|"))
                        + ")(?![A-Za-z0-9_])");
    }

    public boolean isEmpty() {
        return pattern == null;
    }

    /**
     * 创建一个流式清洗器，用于对 SSE 分片做同样的替换。
     *
     * <p>字段名可能被拆到两个分片里（例如 user_ + id），因此不能逐片独立替换。
     * 流式清洗器会缓冲末尾未结束的标识符，直到遇到分隔符或调用 {@link Streaming#flush()}。</p>
     */
    public Streaming streaming() {
        return new Streaming(this);
    }

    /**
     * 替换文本中的真实表名/字段名；入参为 null 时原样返回。
     */
    public String sanitize(String text) {
        if (text == null || text.isEmpty() || pattern == null) {
            return text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String matched = matcher.group(1);
            String label = labels.get(matched.toLowerCase(Locale.ROOT));
            out.append(label == null ? matched : label);
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /**
     * 流式清洗器：尽量多输出已确定安全的内容，只保留末尾可能未写完的标识符。
     */
    public static final class Streaming {

        private final DisplayNameSanitizer sanitizer;
        private final StringBuilder buffer = new StringBuilder();

        private Streaming(DisplayNameSanitizer sanitizer) {
            this.sanitizer = sanitizer;
        }

        /**
         * 追加一个分片，返回当前可以安全下发的已替换文本（可能为空字符串）。
         */
        public String accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return "";
            }
            buffer.append(chunk);
            int safeEnd = safeEnd();
            if (safeEnd <= 0) {
                return "";
            }
            String safe = sanitizer.sanitize(buffer.substring(0, safeEnd));
            buffer.delete(0, safeEnd);
            return safe;
        }

        /**
         * 输出并清空剩余缓冲；流结束时必须调用一次。
         */
        public String flush() {
            if (buffer.length() == 0) {
                return "";
            }
            String rest = sanitizer.sanitize(buffer.toString());
            buffer.setLength(0);
            return rest;
        }

        /**
         * 末尾如果仍是标识符（可能尚未接收完整），整段保留；否则全部可以输出。
         */
        private int safeEnd() {
            int end = buffer.length();
            int start = end;
            while (start > 0 && isIdentifierChar(buffer.charAt(start - 1))) {
                start--;
            }
            return start == end ? end : start;
        }

        private boolean isIdentifierChar(char ch) {
            return ch == '_' || ch == '.' || (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
        }
    }
}
