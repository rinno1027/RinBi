package org.rin.rule;

import org.rin.admin.dto.PageResult;
import org.rin.auth.AuthService;
import org.rin.auth.AuthUser;
import org.rin.rule.dto.StatRuleOption;
import org.rin.rule.dto.StatRuleRequest;
import org.rin.rule.dto.StatRuleView;
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

import java.util.List;

/**
 * 统计规则接口。
 *
 * <p>拥有数据源访问权限的登录用户都可以查看/使用规则；新增、修改、删除仅允许
 * ADMIN，权限在 StatRuleService 中强制校验。</p>
 */
@RestController
@RequestMapping("/api/rules")
public class StatRuleController {

    @Autowired
    private StatRuleService statRuleService;

    @GetMapping
    public PageResult<StatRuleView> list(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String dataSourceKey,
            @RequestParam(required = false) String keyword) {
        return statRuleService.listRules(user, page, size, dataSourceKey, keyword);
    }

    /**
     * 对话页规则多选框候选项。
     */
    @GetMapping("/options")
    public List<StatRuleOption> options(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @RequestParam String dataSourceKey) {
        return statRuleService.listOptions(user, dataSourceKey);
    }

    @GetMapping("/{ruleId}")
    public StatRuleView detail(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @PathVariable Long ruleId) {
        return statRuleService.getRule(user, ruleId);
    }

    @PostMapping
    public StatRuleView create(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @RequestBody(required = false) StatRuleRequest request) {
        return statRuleService.createRule(user, request);
    }

    @PutMapping("/{ruleId}")
    public StatRuleView update(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @PathVariable Long ruleId,
            @RequestBody(required = false) StatRuleRequest request) {
        return statRuleService.updateRule(user, ruleId, request);
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @PathVariable Long ruleId) {
        statRuleService.deleteRule(user, ruleId);
        return ResponseEntity.noContent().build();
    }
}
