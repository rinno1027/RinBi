package org.rin.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.rin.agent.dto.ApiError;
import org.rin.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 登录校验拦截器：拦截 /api/**，登录接口与健康检查除外。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Autowired
    private AuthService authService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检请求不带 token，直接放行。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = AuthService.resolveToken(request);
        try {
            AuthUser user = authService.authenticate(token);
            request.setAttribute(AuthService.AUTH_USER_ATTRIBUTE, user);
            return true;
        } catch (UnauthorizedException e) {
            writeUnauthorized(response, e.getMessage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        request.removeAttribute(AuthService.AUTH_USER_ATTRIBUTE);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        try {
            objectMapper.writeValue(response.getWriter(), ApiError.builder()
                    .code("UNAUTHORIZED")
                    .message(message == null ? "请先登录" : message)
                    .timestamp(OffsetDateTime.now().format(TIMESTAMP_FORMAT))
                    .build());
        } catch (IOException e) {
            log.debug("写入未登录响应失败: {}", e.getMessage());
        }
    }
}
