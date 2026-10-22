package org.rin.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注册申请结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterResponse {

    private Long id;

    private String username;

    private String displayName;

    /**
     * 注册后的账号状态，固定为 2（待审批）。
     */
    private Integer status;

    private String message;
}
