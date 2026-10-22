package org.rin.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 流中的异常事件，前端按 ApiError 的方式展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamError {

    private String code;

    private String message;

    private Integer status;
}
