package org.rin.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 由模型给出、后端校验后的图表描述。
 *
 * <p>只描述“怎么画”，不携带数据：前端根据 {@link ChatResponse#getTable()} 的列名与行数据
 * 取值，再用 ECharts 渲染。这样可以避免模型输出 HTML/JS，也避免重复传输数据。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChartSpec {

    /**
     * 图表类型：bar、line、pie、scatter。
     */
    private String type;

    /**
     * 图表标题，由模型生成，可能为空。
     */
    private String title;

    /**
     * 横轴/分类列名，必须与查询结果列名完全一致。
     */
    @JsonProperty("xField")
    private String xField;

    /**
     * 数值列名，必须与查询结果列名完全一致；饼图只能有一个。
     */
    @JsonProperty("yFields")
    @Builder.Default
    private List<String> yFields = new ArrayList<>();

    /**
     * 数值单位，例如“个”“元”“%”，可为空。
     */
    private String unit;

    /**
     * Jackson 默认会把 getXField() 推导成 xfield，这里显式声明 getter 与 JSON 名称，
     * 避免序列化结果中同时出现 xfield 与 xField。
     */
    @JsonProperty("xField")
    public String getXField() {
        return xField;
    }

    @JsonProperty("yFields")
    public List<String> getYFields() {
        return yFields;
    }
}
