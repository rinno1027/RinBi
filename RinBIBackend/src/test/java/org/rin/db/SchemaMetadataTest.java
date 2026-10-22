package org.rin.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证表/字段注释到展示名的映射规则。
 */
class SchemaMetadataTest {

    private final SchemaMetadata metadata = new SchemaMetadata(
            Map.of("customer", "客户"),
            Map.of("customer.id", "客户编号",
                    "customer.region", "所属地区",
                    "sales_order.amount", "订单金额"),
            Map.of("id", "客户编号",
                    "region", "所属地区",
                    "amount", "订单金额",
                    "level", "客户等级",
                    "status", "订单状态"),
            Set.of("id"));

    @Test
    void prefersQualifiedColumnThenUniqueColumnName() {
        assertEquals("客户编号", metadata.columnComment("customer", "id"));
        assertEquals("订单金额", metadata.columnComment("SALES_ORDER", "amount"));
        assertEquals("所属地区", metadata.columnComment(null, "region"));
        assertEquals("客户", metadata.tableComment("CUSTOMER"));
    }

    @Test
    void returnsNullWhenColumnIsAmbiguousWithoutTable() {
        assertNull(metadata.columnComment(null, "id"));
        assertNull(metadata.columnComment("unknown", "id"));
    }

    @Test
    void mapsLabelsAndKeepsDisplayColumnsUnique() {
        List<ColumnRef> refs = Arrays.asList(
                new ColumnRef("region", "region", "customer"),
                new ColumnRef("customer_name", "customer_name", "customer"),
                new ColumnRef("id", "id", null),
                new ColumnRef("region", "region", "customer"));

        List<String> columns = metadata.displayColumns(refs,
                Arrays.asList("region", "customer_name", "id", "region"));

        assertEquals(Arrays.asList("所属地区", "customer_name", "id", "所属地区_2"), columns);
    }

    @Test
    void buildsIdentifierLabelsWithoutAmbiguousNames() {
        Map<String, String> labels = new LinkedHashMap<>(metadata.identifierLabels());

        assertEquals("客户", labels.get("customer"));
        assertEquals("所属地区", labels.get("region"));
        assertEquals("客户编号", labels.get("customer.id"));
        assertEquals("订单金额", labels.get("amount"));
        assertNull(labels.get("id"), "歧义字段不应参与全文替换");
    }
}
