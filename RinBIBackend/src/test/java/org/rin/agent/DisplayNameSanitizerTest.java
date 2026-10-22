package org.rin.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证回答兜底替换以及流式分片处理。
 */
class DisplayNameSanitizerTest {

    private DisplayNameSanitizer sanitizer() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("customer", "客户");
        labels.put("customer_id", "客户编号");
        labels.put("customer.id", "客户编号");
        labels.put("region", "所属地区");
        return new DisplayNameSanitizer(labels);
    }

    @Test
    void sanitizesWholeTextWithoutTouchingLongerWords() {
        String text = "customer_id 对应的 customer.id 与 customer 在 region 区域，regional 保持不变。";

        assertEquals("客户编号 对应的 客户编号 与 客户 在 所属地区 区域，regional 保持不变。",
                sanitizer().sanitize(text));
    }

    @Test
    void streamingHoldsBackIdentifierSplitAcrossChunks() {
        DisplayNameSanitizer.Streaming streaming = sanitizer().streaming();

        assertEquals("按 ", streaming.accept("按 reg"));
        assertEquals("所属地区 汇总，", streaming.accept("ion 汇总，"));
        assertEquals("", streaming.accept("customer_"));
        assertEquals("客户编号 共 3 个。", streaming.accept("id 共 3 个。"));
        assertEquals("", streaming.flush());
    }

    @Test
    void streamingFlushesTrailingIdentifier() {
        DisplayNameSanitizer.Streaming streaming = sanitizer().streaming();

        assertEquals("字段 ", streaming.accept("字段 region"));
        assertEquals("所属地区", streaming.flush());
        assertEquals("", streaming.flush());
    }

    @Test
    void ignoresEmptyLabelsAndNamesEqualLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("usage", "usage");
        labels.put("", "空");
        labels.put(null, "空");
        DisplayNameSanitizer sanitizer = new DisplayNameSanitizer(labels);

        assertTrue(sanitizer.isEmpty());
        assertEquals("usage", sanitizer.sanitize("usage"));
    }
}
