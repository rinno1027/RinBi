package org.rin.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 便于前端渲染的结构化表格。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryTable {

    @Builder.Default
    private List<String> columns = new ArrayList<>();

    @Builder.Default
    private List<List<String>> rows = new ArrayList<>();
}
