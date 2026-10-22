package org.rin.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.rin.auth.dto.LoginRequest;
import org.rin.auth.dto.LoginResponse;
import org.rin.auth.dto.RegisterRequest;
import org.rin.auth.dto.RegisterResponse;
import org.rin.auth.dto.UserInfo;
import org.rin.auth.entity.SysUser;
import org.rin.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录相关接口：注册申请、登录、退出、当前用户。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * 提交注册申请。注册成功后状态为待审批，不会签发 token。
     */
    @PostMapping("/register")
    public RegisterResponse register(@RequestBody(required = false) RegisterRequest request) {
        if (request == null) {
            throw new BadRequestException("注册请求体不能为空");
        }
        SysUser user = authService.register(request.getUsername(), request.getPassword(),
                request.getDisplayName());
        return RegisterResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .status(user.getStatus())
                .message("注册申请已提交，请等待管理员审批")
                .build();
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody(required = false) LoginRequest request) {
        if (request == null) {
            throw new BadRequestException("登录请求体不能为空");
        }
        AuthService.LoginResult result = authService.login(request.getUsername(), request.getPassword());
        return LoginResponse.builder()
                .token(result.token())
                .expiresAt(result.expiresAt())
                .user(UserInfo.from(result.user()))
                .build();
    }

    @GetMapping("/me")
    public UserInfo me(@RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user) {
        return UserInfo.from(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        authService.logout(AuthService.resolveToken(request));
        return ResponseEntity.noContent().build();
    }
}
