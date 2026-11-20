package org.rin.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rin.admin.dto.PageResult;
import org.rin.auth.AuthUser;
import org.rin.db.entity.DataSourceConfig;
import org.rin.db.mapper.DataSourceConfigMapper;
import org.rin.exception.BadRequestException;
import org.rin.exception.ForbiddenException;
import org.rin.exception.NotFoundException;
import org.rin.rule.dto.StatRuleInputFieldRequest;
import org.rin.rule.dto.StatRuleInputFieldView;
import org.rin.rule.dto.StatRuleOption;
import org.rin.rule.dto.StatRulePrompt;
import org.rin.rule.dto.StatRuleRequest;
import org.rin.rule.dto.StatRuleView;
import org.rin.rule.entity.StatRule;
import org.rin.rule.entity.StatRuleInputField;
import org.rin.rule.mapper.StatRuleInputFieldMapper;
import org.rin.rule.mapper.StatRuleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 统计规则管理，以及对话规划阶段的规则注入。
 *
 * <p>统计规则按数据源隔离，拥有数据源访问权限的用户可以查看和使用规则；
 * 新增、修改、删除仅允许 ADMIN，普通用户只读。对话时只有用户明确选择数据源
 * 才会加载规则，且规则只作为输入上下文传给规划模型，不参与 SQL 执行或结果后处理。</p>
 */
@Service
public class StatRuleService {

    /** 单次对话最多注入的规则数，避免提示词无限膨胀。 */
    public static final int MAX_RULES_PER_CHAT = 50;

    private static final int MAX_INPUT_FIELDS = 50;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 100;

    private static final int CODE_MAX = 50;
    private static final int NAME_MAX = 128;
    private static final int DESCRIPTION_MAX = 512;
    private static final int FORMULA_MAX = 1024;
    private static final int BUSINESS_RULE_MAX = 4000;
    private static final int REMARK_MAX = 512;
    private static final int SOURCE_TABLE_MAX = 128;
    private static final int FIELD_NAME_MAX = 128;
    private static final int FIELD_DESCRIPTION_MAX = 512;

    @Autowired
    private StatRuleMapper ruleMapper;

    @Autowired
    private StatRuleInputFieldMapper inputFieldMapper;

    @Autowired
    private DataSourceConfigMapper dataSourceConfigMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 分页查询当前用户数据源范围内的规则；列表不返回输入字段明细，详情接口再加载。
     */
    public PageResult<StatRuleView> listRules(AuthUser actor, int page, int size,
                                              String dataSourceKey, String keyword) {
        int safePage = normalizePage(page);
        int safeSize = normalizeSize(size);
        String sourceKey = trimToNull(dataSourceKey);
        if (sourceKey != null) {
            requireDataSourceAccess(actor.id(), sourceKey);
        }
        String term = trimToNull(keyword);
        if (term != null && term.length() > MAX_KEYWORD_LENGTH) {
            term = term.substring(0, MAX_KEYWORD_LENGTH);
        }
        long total = ruleMapper.countAccessible(actor.id(), sourceKey, term);
        List<StatRuleView> items = ruleMapper.selectAccessiblePage(
                        actor.id(), sourceKey, term, (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(rule -> toView(rule, List.of(), actor.isAdmin()))
                .toList();
        return PageResult.of(items, total, safePage, safeSize);
    }

    public List<StatRuleOption> listOptions(AuthUser actor, String dataSourceKey) {
        String sourceKey = requireText(dataSourceKey, "数据源", 64);
        requireDataSourceAccess(actor.id(), sourceKey);
        return ruleMapper.selectOptions(actor.id(), sourceKey);
    }

    public StatRuleView getRule(AuthUser actor, Long ruleId) {
        StatRule rule = ruleMapper.selectAccessibleById(actor.id(), ruleId);
        if (rule == null) {
            throw new NotFoundException("统计规则不存在或无权访问");
        }
        return toView(rule, loadFields(List.of(ruleId)).getOrDefault(ruleId, List.of()),
                actor.isAdmin());
    }

    @Transactional
    public StatRuleView createRule(AuthUser actor, StatRuleRequest request) {
        requireAdmin(actor);
        NormalizedRule data = normalize(request);
        requireDataSourceAccess(actor.id(), data.dataSourceKey());
        ensureRuleCodeAvailable(data.ruleCode(), null);

        LocalDateTime now = LocalDateTime.now();
        StatRule rule = new StatRule();
        rule.setDataSourceKey(data.dataSourceKey());
        rule.setVersionNo(initialVersion());
        rule.setRuleCode(data.ruleCode());
        applyBusinessFields(rule, data);
        rule.setCreatedBy(actor.id());
        rule.setUpdatedBy(actor.id());
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new BadRequestException("规则代码已存在：" + data.ruleCode());
        }
        insertFields(rule.getId(), data.inputFields(), actor.id(), now);
        return getRule(actor, rule.getId());
    }

    @Transactional
    public StatRuleView updateRule(AuthUser actor, Long ruleId, StatRuleRequest request) {
        requireAdmin(actor);
        StatRule existing = ruleMapper.selectById(ruleId);
        if (existing == null) {
            throw new NotFoundException("统计规则不存在");
        }
        requireDataSourceAccess(actor.id(), existing.getDataSourceKey());
        NormalizedRule data = normalize(request);
        requireDataSourceAccess(actor.id(), data.dataSourceKey());
        ensureRuleCodeAvailable(data.ruleCode(), ruleId);

        LocalDateTime now = LocalDateTime.now();
        existing.setDataSourceKey(data.dataSourceKey());
        existing.setRuleCode(data.ruleCode());
        applyBusinessFields(existing, data);
        existing.setVersionNo(nextVersion(existing.getVersionNo()));
        existing.setUpdatedBy(actor.id());
        existing.setUpdatedAt(now);
        try {
            ruleMapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            throw new BadRequestException("规则代码已存在：" + data.ruleCode());
        }
        inputFieldMapper.delete(new LambdaQueryWrapper<StatRuleInputField>()
                .eq(StatRuleInputField::getRuleId, ruleId));
        insertFields(ruleId, data.inputFields(), actor.id(), now);
        return getRule(actor, ruleId);
    }

    @Transactional
    public void deleteRule(AuthUser actor, Long ruleId) {
        requireAdmin(actor);
        StatRule existing = ruleMapper.selectById(ruleId);
        if (existing == null) {
            throw new NotFoundException("统计规则不存在");
        }
        requireDataSourceAccess(actor.id(), existing.getDataSourceKey());
        inputFieldMapper.delete(new LambdaQueryWrapper<StatRuleInputField>()
                .eq(StatRuleInputField::getRuleId, ruleId));
        ruleMapper.deleteById(ruleId);
    }

    /**
     * 校验对话勾选的规则并序列化为规划提示词中的 JSON 数组。
     *
     * @return 没有勾选规则时返回空字符串
     * @throws BadRequestException 规则 ID 非法
     * @throws ForbiddenException  规则不属于当前用户可访问的数据源
     */
    public String buildPromptJson(Long userId, String dataSourceKey, List<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return "";
        }
        String sourceKey = requireText(dataSourceKey, "数据源", 64);
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long ruleId : ruleIds) {
            if (ruleId == null || ruleId <= 0) {
                throw new BadRequestException("统计规则 ID 不合法");
            }
            uniqueIds.add(ruleId);
        }
        if (uniqueIds.isEmpty()) {
            return "";
        }
        if (uniqueIds.size() > MAX_RULES_PER_CHAT) {
            throw new BadRequestException("单次对话最多选择 " + MAX_RULES_PER_CHAT + " 条统计规则");
        }
        requireDataSourceAccess(userId, sourceKey);

        List<Long> ids = List.copyOf(uniqueIds);
        List<StatRule> rules = ruleMapper.selectAccessibleByIds(userId, sourceKey, ids);
        if (rules.size() != ids.size()) {
            throw new ForbiddenException("部分统计规则不存在或无权访问");
        }
        Map<Long, List<StatRuleInputField>> fields = loadFields(ids);
        List<StatRulePrompt> prompts = new ArrayList<>();
        for (StatRule rule : rules) {
            List<StatRulePrompt.InputField> promptFields = fields
                    .getOrDefault(rule.getId(), List.of())
                    .stream()
                    .map(field -> StatRulePrompt.InputField.builder()
                            .sourceTable(field.getSourceTable())
                            .fieldName(field.getFieldName())
                            .fieldDescription(field.getFieldDescription())
                            .build())
                    .toList();
            prompts.add(StatRulePrompt.builder()
                    .ruleCode(rule.getRuleCode())
                    .ruleName(rule.getRuleName())
                    .ruleDescription(rule.getRuleDescription())
                    .formula(rule.getFormula())
                    .businessRule(rule.getBusinessRule())
                    .remark(rule.getRemark())
                    .versionNo(rule.getVersionNo())
                    .inputFields(promptFields.isEmpty() ? null : promptFields)
                    .build());
        }
        try {
            return objectMapper.writeValueAsString(prompts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("统计规则序列化失败", e);
        }
    }


    private void ensureRuleCodeAvailable(String ruleCode, Long excludeId) {
        int count = ruleMapper.countByRuleCodeExcluding(ruleCode, excludeId);
        if (count > 0) {
            throw new BadRequestException("规则代码已存在：" + ruleCode);
        }
    }

    private void applyBusinessFields(StatRule rule, NormalizedRule data) {
        rule.setRuleName(data.ruleName());
        rule.setRuleDescription(data.ruleDescription());
        rule.setFormula(data.formula());
        rule.setBusinessRule(data.businessRule());
        rule.setRemark(data.remark());
    }

    private void insertFields(Long ruleId, List<NormalizedField> fields,
                              Long userId, LocalDateTime now) {
        for (int index = 0; index < fields.size(); index++) {
            NormalizedField field = fields.get(index);
            StatRuleInputField entity = new StatRuleInputField();
            entity.setRuleId(ruleId);
            entity.setSourceTable(field.sourceTable());
            entity.setFieldName(field.fieldName());
            entity.setFieldDescription(field.fieldDescription());
            entity.setSortOrder(index);
            entity.setCreatedBy(userId);
            entity.setUpdatedBy(userId);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            try {
                inputFieldMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                throw new BadRequestException("输入字段重复：" + field.sourceTable()
                        + "." + field.fieldName());
            }
        }
    }

    private Map<Long, List<StatRuleInputField>> loadFields(List<Long> ruleIds) {
        List<StatRuleInputField> rows = inputFieldMapper.selectByRuleIds(ruleIds);
        Map<Long, List<StatRuleInputField>> grouped = new LinkedHashMap<>();
        for (StatRuleInputField row : rows) {
            grouped.computeIfAbsent(row.getRuleId(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private StatRuleView toView(StatRule rule, List<StatRuleInputField> fields, boolean canEdit) {
        List<StatRuleInputFieldView> fieldViews = fields.stream()
                .map(field -> StatRuleInputFieldView.builder()
                        .id(field.getId())
                        .sourceTable(field.getSourceTable())
                        .fieldName(field.getFieldName())
                        .fieldDescription(field.getFieldDescription())
                        .sortOrder(field.getSortOrder())
                        .build())
                .toList();
        return StatRuleView.builder()
                .id(rule.getId())
                .dataSourceKey(rule.getDataSourceKey())
                .dataSourceName(rule.getDataSourceName())
                .versionNo(rule.getVersionNo())
                .ruleCode(rule.getRuleCode())
                .ruleName(rule.getRuleName())
                .ruleDescription(rule.getRuleDescription())
                .formula(rule.getFormula())
                .businessRule(rule.getBusinessRule())
                .remark(rule.getRemark())
                .createdBy(rule.getCreatedBy())
                .createdByName(rule.getCreatedByName())
                .updatedBy(rule.getUpdatedBy())
                .updatedByName(rule.getUpdatedByName())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .canEdit(canEdit)
                .inputFields(fieldViews)
                .build();
    }

    private NormalizedRule normalize(StatRuleRequest request) {
        if (request == null) {
            throw new BadRequestException("请求体不能为空");
        }
        String dataSourceKey = requireText(request.getDataSourceKey(), "数据源", 64);
        String ruleCode = requireText(request.getRuleCode(), "规则代码", CODE_MAX);
        String ruleName = requireText(request.getRuleName(), "规则名称", NAME_MAX);
        String description = optionalText(request.getRuleDescription(), "规则描述", DESCRIPTION_MAX);
        String formula = optionalText(request.getFormula(), "计算公式", FORMULA_MAX);
        String businessRule = optionalText(request.getBusinessRule(), "业务规则", BUSINESS_RULE_MAX);
        String remark = optionalText(request.getRemark(), "备注", REMARK_MAX);

        List<StatRuleInputFieldRequest> rawFields = request.getInputFields();
        List<NormalizedField> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (rawFields != null) {
            if (rawFields.size() > MAX_INPUT_FIELDS) {
                throw new BadRequestException("输入字段最多 " + MAX_INPUT_FIELDS + " 个");
            }
            for (StatRuleInputFieldRequest raw : rawFields) {
                if (raw == null) {
                    continue;
                }
                String sourceTable = trimToNull(raw.getSourceTable());
                String fieldName = trimToNull(raw.getFieldName());
                String fieldDescription = trimToNull(raw.getFieldDescription());
                if (sourceTable == null && fieldName == null && fieldDescription == null) {
                    continue;
                }
                sourceTable = requireText(sourceTable, "来源表", SOURCE_TABLE_MAX);
                fieldName = requireText(fieldName, "字段名", FIELD_NAME_MAX);
                fieldDescription = optionalText(fieldDescription, "字段说明",
                        FIELD_DESCRIPTION_MAX);
                String key = (sourceTable + "\u0000" + fieldName).toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    throw new BadRequestException("输入字段重复：" + sourceTable + "." + fieldName);
                }
                fields.add(new NormalizedField(sourceTable, fieldName, fieldDescription));
            }
        }
        return new NormalizedRule(dataSourceKey, ruleCode, ruleName, description,
                formula, businessRule, remark, List.copyOf(fields));
    }

    private void requireAdmin(AuthUser actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new ForbiddenException("仅管理员可以维护统计规则");
        }
    }

    private DataSourceConfig requireDataSourceAccess(Long userId, String dataSourceKey) {
        DataSourceConfig source = dataSourceConfigMapper.selectOwnedByUser(userId, dataSourceKey);
        if (source != null) {
            return source;
        }
        if (dataSourceConfigMapper.countEnabledByKey(dataSourceKey) > 0) {
            throw new ForbiddenException("无权访问数据源：" + dataSourceKey);
        }
        throw new BadRequestException("数据源不存在或已停用：" + dataSourceKey);
    }

    private String requireText(String value, String label, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new BadRequestException(label + "不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new BadRequestException(label + "不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    private String optionalText(String value, String label, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > maxLength) {
            throw new BadRequestException(label + "不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String initialVersion() {
        return Long.toString(System.currentTimeMillis());
    }

    private String nextVersion(String current) {
        long now = System.currentTimeMillis();
        String candidate = Long.toString(now);
        return candidate.equals(current) ? Long.toString(now + 1) : candidate;
    }

    private record NormalizedRule(String dataSourceKey,
                                  String ruleCode,
                                  String ruleName,
                                  String ruleDescription,
                                  String formula,
                                  String businessRule,
                                  String remark,
                                  List<NormalizedField> inputFields) {
    }

    private record NormalizedField(String sourceTable,
                                   String fieldName,
                                   String fieldDescription) {
    }
}

