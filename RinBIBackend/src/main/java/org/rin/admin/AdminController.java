package org.rin.admin;

import org.rin.admin.dto.AdminDataSourceView;
import org.rin.admin.dto.AdminUserView;
import org.rin.admin.dto.DataSourceAccessRequest;
import org.rin.admin.dto.DataSourceAccessView;
import org.rin.admin.dto.DataSourceConfigRequest;
import org.rin.admin.dto.PageResult;
import org.rin.auth.AuthService;
import org.rin.auth.AuthUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员接口：用户审批、全局数据源配置与按用户授权。
 *
 * <p>路径统一在 /api/admin/** 下，由登录拦截器保护，并在 AdminService
 * 中再次校验 ADMIN 角色。</p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    @GetMapping("/users")
    public PageResult<AdminUserView> users(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String status) {
        return adminService.listUsers(actor, page, size, keyword, status);
    }

    @PostMapping("/users/{userId}/approve")
    public AdminUserView approve(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser actor,
            @PathVariable Long userId) {
        return adminService.approveUser(actor, userId);
    }

    @PostMapping("/users/{userId}/disable")
    public AdminUserView disable(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser actor,
            @PathVariable Long userId) {
        return adminService.disableUser(actor, userId);
    }

    @GetMapping("/datasources")
    public PageResult<AdminDataSourceView> dataSources(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String enabled) {
        return adminService.listDataSources(actor, page, size, keyword, enabled);
    }

    @PostMapping("/datasources")
    public AdminDataSourceView createDataSource(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser actor,
            @RequestBody(required = false) DataSourceConfigRequest request) {
        return adminService.createDataSource(actor, request);
    }

    @PutMapping("/datasources/{dataSourceId}")
    public AdminDataSourceView updateDataSource(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser actor,
            @PathVariable Long dataSourceId,
            @RequestBody(required = false) DataSourceConfigRequest request) {
        return adminService.updateDataSource(actor, dataSourceId, request);
    }

    @DeleteMapping("/datasources/{dataSourceId}")
    public ResponseEntity<Void> deleteDataSource(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser actor,
            @PathVariable Long dataSourceId) {
        adminService.deleteDataSource(actor, dataSourceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/datasources/{dataSourceId}/users")
    public DataSourceAccessView dataSourceUsers(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser actor,
            @PathVariable Long dataSourceId) {
        return adminService.listDataSourceAccess(actor, dataSourceId);
    }

    @PutMapping("/datasources/{dataSourceId}/users")
    public ResponseEntity<Void> assignDataSourceUsers(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser actor,
            @PathVariable Long dataSourceId,
            @RequestBody(required = false) DataSourceAccessRequest request) {
        adminService.assignDataSourceUsers(actor, dataSourceId, request);
        return ResponseEntity.noContent().build();
    }
}
