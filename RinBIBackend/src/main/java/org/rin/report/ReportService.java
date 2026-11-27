package org.rin.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rin.agent.AgentStatus;
import org.rin.agent.LlmService;
import org.rin.agent.dto.ChatResponse;
import org.rin.config.AgentProperties;
import org.rin.db.DataSourceDefinition;
import org.rin.db.DatabaseRegistry;
import org.rin.db.QueryExecutor;
import org.rin.db.QueryResult;
import org.rin.db.SchemaService;
import org.rin.exception.BadRequestException;
import org.rin.exception.ChatCancelledException;
import org.rin.exception.InputLimitException;
import org.rin.exception.NotFoundException;
import org.rin.history.HistoryService;
import org.rin.history.HistoryService.HistoryExchange;
import org.rin.history.entity.ChatConversation;
import org.rin.report.dto.ReportChatRequest;
import org.rin.report.dto.ReportFileInfo;
import org.rin.report.dto.ReportPlan;
import org.rin.report.dto.ReportTemplateInfo;
import org.rin.report.entity.ReportFileEntity;
import org.rin.report.entity.ReportTemplateEntity;
import org.rin.report.excel.ExcelReportFiller;
import org.rin.report.excel.ExcelTemplate;
import org.rin.report.excel.ExcelTemplateParser;
import org.rin.report.excel.ReportFillPlan;
import org.rin.report.mapper.ReportFileMapper;
import org.rin.rule.StatRuleService;
import org.rin.task.ChatTask;
import org.rin.task.ChatTaskRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 智能报表主流程：导入模板（落盘 + report_template）→ 在报表会话中按需求规划
 * → 只读校验并执行 SQL → POI 回填 → 保存文件与记录 → 写入历史会话。
 *
 * <p>一个会话绑定一个模板；后续每轮请求都会带上上一轮的取数计划与最近几轮需求，
 * 让模型在保持模板结构的前提下调整报表内容，并受单轮/会话累计输入上限约束。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final int MAX_REQUIREMENT_LENGTH = 2000;
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern ALIAS_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XLS_CONTENT_TYPE = "application/vnd.ms-excel";

    @Autowired
    private ExcelTemplateParser templateParser;

    @Autowired
    private ExcelReportFiller reportFiller;

    @Autowired
    private LlmService llmService;

    @Autowired
    private SchemaService schemaService;

    @Autowired
    private DatabaseRegistry databaseRegistry;

    @Autowired
    private QueryExecutor queryExecutor;

    @Autowired
    private ReportFileMapper fileMapper;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AgentProperties properties;

    @Autowired
    private StatRuleService statRuleService;

    @Autowired
    private ReportTemplateStore templateStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatTaskRegistry chatTaskRegistry;

    /**
     * 导入 Excel 模板：模板文件与导出的报表一样保存到服务器，
     * 元信息写入 report_template，并创建（或复用）一个智能报表会话。
     *
     * <p>一个会话只允许导入一个模板；需要更换模板时请新建报表会话。</p>
     */
    public ReportTemplateInfo uploadTemplate(Long userId, MultipartFile file, String conversationId) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请上传 Excel 模板文件");
        }
        validateFile(file);
        String originalName = sanitizeFileName(file.getOriginalFilename());
        byte[] templateBytes;
        try {
            templateBytes = file.getBytes();
        } catch (IOException e) {
            log.warn("读取上传的报表模板失败: file={}", originalName, e);
            throw new BadRequestException("读取上传文件失败：" + safeMessage(e));
        }

        // 先解析一次，确认上传的确实是可读的 Excel 模板，同时生成结构摘要
        ExcelTemplate template = templateParser.parse(templateBytes);
        String conversation = resolveReportConversationId(conversationId);
        ChatConversation existing = historyService.findConversation(userId, conversation);
        if (existing != null && !HistoryService.FEATURE_REPORT.equalsIgnoreCase(
                defaultValue(existing.getFeatureType(), HistoryService.FEATURE_DATA_QA))) {
            throw new BadRequestException("该会话已用于数据问答，智能报表请新建会话后再上传模板");
        }
        if (templateStore.findByConversation(userId, conversation) != null) {
            throw new BadRequestException("该会话已导入模板，一个会话只支持一个模板；"
                    + "如需更换模板请新建报表会话");
        }

        ReportTemplateEntity entity = new ReportTemplateEntity();
        entity.setUserId(userId);
        entity.setConversationId(conversation);
        entity.setTemplateName(originalName);
        entity.setSheetSummary(summarizeTemplate(template));
        templateStore.save(entity, templateBytes);

        ChatResponse response = ChatResponse.builder()
                .conversationId(conversation)
                .status(AgentStatus.SUCCESS)
                .answer(buildTemplateAnswer(entity))
                .reason("")
                .template(toTemplateInfo(entity))
                .promptLimit(maxPromptChars())
                .inputChars(historyService.inputChars(userId, conversation))
                .inputLimit(historyService.maxConversationChars())
                .timestamp(OffsetDateTime.now().format(TIMESTAMP_FORMAT))
                .build();
        try {
            historyService.recordExchange(userId, conversation, "上传模板：" + originalName,
                    null, response, HistoryService.FEATURE_REPORT);
        } catch (RuntimeException e) {
            // 历史写入失败不影响模板下载与后续生成
            log.warn("写入模板上传记录失败: conversation={}", conversation, e);
            response.getWarnings().add("模板已保存，但历史会话写入失败");
        }
        return toTemplateInfo(entity);
    }

    /**
     * 生成报表并返回与普通对话一致的响应结构（兼容一次性生成的旧接口）。
     *
     * <p>模板会被保存到服务器并创建独立的报表会话，随后立即按需求生成一次报表；
     * 后续可以在同一会话中继续发送调整要求。</p>
     */
    public ChatResponse generate(Long userId, MultipartFile file, String database,
                                 String requirement, String conversationId) {
        return generate(userId, file, database, requirement, conversationId, null);
    }

    /**
     * 生成报表；{@code ruleIds} 为当前用户勾选的统计规则，规则只用于约束报表取数口径。
     */
    public ChatResponse generate(Long userId, MultipartFile file, String database,
                                 String requirement, String conversationId,
                                 List<Long> ruleIds) {
        ReportTemplateInfo template = uploadTemplate(userId, file, conversationId);
        return chat(userId, template.getConversationId(), template.getTemplateId(), database,
                requirement, ruleIds);
    }

    /**
     * 报表会话中的一次调整请求（JSON 入口）。
     */
    public ChatResponse chat(Long userId, ReportChatRequest request) {
        if (request == null) {
            throw new BadRequestException("请求体不能为空");
        }
        return chat(userId, request.getConversationId(), request.getTemplateId(),
                request.getDatabase(), request.getQuestion(), request.getRuleIds());
    }

    /**
     * 智能报表会话的一次取数/调整：复用会话绑定的模板，按本轮需求重新规划 SQL 与填充映射。
     * 累计输入超过 {@code agent.report.max-conversation-chars} 时要求新建会话并重新上传模板。
     */
    public ChatResponse chat(Long userId, String conversationId, Long templateId, String database,
                             String requirement, List<Long> ruleIds) {
        String conversation = normalize(conversationId);
        if (conversation == null) {
            throw new BadRequestException("conversationId 不能为空，请先上传 Excel 模板创建报表会话");
        }
        validateConversationId(conversation);
        // 注册可取消任务：前端点击停止或关闭页面时，会通过
        // POST /api/agent/conversations/{id}/stop 取消它并中断本次生成。
        ChatTask task = chatTaskRegistry.start(userId, conversation);
        task.bind();
        try {
            return doChat(userId, conversation, templateId, database, requirement, ruleIds, task);
        } finally {
            task.unbind();
            chatTaskRegistry.finish(task);
        }
    }

    /**
     * 报表生成主流程；{@code task} 用于在用户停止或连接断开后立即结束模型调用与取数，
     * 被取消时抛出 {@link ChatCancelledException}，不写入取数计划与历史记录。
     */
    private ChatResponse doChat(Long userId, String conversation, Long templateId, String database,
                                String requirement, List<Long> ruleIds, ChatTask task) {
        long start = System.currentTimeMillis();

        ReportTemplateEntity templateEntity = templateId == null
                ? templateStore.findByConversation(userId, conversation)
                : requireTemplate(userId, templateId);
        if (templateEntity == null) {
            throw new BadRequestException("当前会话尚未导入 Excel 模板，请先上传模板");
        }
        if (!conversation.equals(templateEntity.getConversationId())) {
            throw new BadRequestException("该模板不属于当前会话，请重新上传模板");
        }
        byte[] templateBytes = templateStore.read(templateEntity);
        String templateName = defaultValue(templateEntity.getTemplateName(), "report-template.xlsx");

        List<String> availableDatabases = databaseRegistry.getKeys(userId);
        if (availableDatabases.isEmpty()) {
            throw new BadRequestException("当前用户没有可用的数据源，请先在数据源页面完成授权");
        }
        String requestedDatabase = normalize(database);
        if (requestedDatabase != null && !availableDatabases.contains(requestedDatabase)) {
            throw new BadRequestException("未授权的数据库: " + requestedDatabase
                    + "，可用数据库: " + availableDatabases);
        }
        String prompt = normalize(requirement);
        if (prompt != null && prompt.length() > MAX_REQUIREMENT_LENGTH) {
            throw new BadRequestException("报表需求长度不能超过 " + MAX_REQUIREMENT_LENGTH + " 字符");
        }

        List<Long> selectedRuleIds = new ArrayList<>();
        if (ruleIds != null) {
            for (Long ruleId : ruleIds) {
                if (ruleId != null) {
                    selectedRuleIds.add(ruleId);
                }
            }
        }
        if (requestedDatabase == null && !selectedRuleIds.isEmpty()) {
            throw new BadRequestException("未指定数据源时不能使用统计规则");
        }
        String rulePromptJson = statRuleService.buildPromptJson(
                userId, requestedDatabase, selectedRuleIds);

        ExcelTemplate template = templateParser.parse(templateBytes);
        String templateText = templateParser.toPromptText(template);
        String schemaText = requestedDatabase == null
                ? schemaService.getAllSchemas(userId)
                : schemaService.getSchema(userId, requestedDatabase);
        String effectivePrompt = prompt == null
                ? "请根据模板结构生成一份完整的数据报表"
                : prompt;
        String previousPlanJson = templateEntity.getPlanJson();
        String historyText = buildReportHistory(userId, conversation);

        // 输入上限：先校验单轮提示词，再校验会话累计消耗，避免超长上下文直接打到模型
        int promptChars = llmService.reportPromptChars(effectivePrompt, requestedDatabase,
                schemaText, templateText, rulePromptJson, previousPlanJson, historyText);
        int promptLimit = maxPromptChars();
        if (promptChars > promptLimit) {
            throw new InputLimitException("本轮报表输入已达到模型单轮上限（" + promptChars + "/"
                    + promptLimit + " 字符），请精简需求，或新建报表会话并重新上传模板");
        }
        long usedChars = historyService.inputChars(userId, conversation);
        int totalLimit = historyService.maxConversationChars();
        if (usedChars + promptChars > totalLimit) {
            throw new InputLimitException("当前报表会话累计输入已达到模型上限（"
                    + (usedChars + promptChars) + "/" + totalLimit
                    + " 字符），请新建会话并重新上传模板后继续调整");
        }

        ReportPlan plan;
        throwIfCancelled(task);
        try {
            plan = llmService.planReport(effectivePrompt, requestedDatabase, schemaText, templateText,
                    rulePromptJson, previousPlanJson, historyText, task);
        } catch (ChatCancelledException e) {
            // 取消不能被下面的“计划生成失败”包装掉，否则会返回 400 而不是停止本次生成。
            throw e;
        } catch (RuntimeException e) {
            log.warn("生成报表填充计划失败: database={}, requirement={}", requestedDatabase, prompt, e);
            throw new BadRequestException("生成报表填充计划失败：" + safeMessage(e));
        }
        throwIfCancelled(task);
        if (!plan.isFeasiblePlan()) {
            throw new BadRequestException("无法根据当前模板生成报表："
                    + defaultValue(plan.getReason(), "模板与可用数据结构不匹配"));
        }

        String databaseKey = requestedDatabase != null
                ? requestedDatabase
                : resolveDatabase(userId, plan.getDatabase(), availableDatabases);
        DataSourceDefinition definition = databaseRegistry.requireOwned(userId, databaseKey);
        List<ReportPlan.SqlSpec> sqls = validateSqlSpecs(plan);
        Map<String, QueryResult> results = executeSqls(userId, databaseKey, sqls, task);
        throwIfCancelled(task);
        List<String> warnings = new ArrayList<>();
        ReportFillPlan fillPlan = buildFillPlan(template, plan, results, warnings);
        if ((fillPlan.getCells() == null || fillPlan.getCells().isEmpty())
                && (fillPlan.getTables() == null || fillPlan.getTables().isEmpty())) {
            throw new BadRequestException("模型未返回任何可用的填充映射，请补充模板表头或调整报表需求");
        }

        throwIfCancelled(task);
        byte[] output = reportFiller.fill(templateBytes, fillPlan);
        String question = prompt == null
                ? "使用模板 " + templateName + " 生成报表"
                : prompt;
        // 停止按钮可能在回填阶段才到达：不要再写出文件，避免留下无人认领的报表。
        throwIfCancelled(task);
        SavedFile saved = saveFile(userId, conversation, templateEntity.getId(),
                templateName, extension(templateName), output);

        int rowCount = results.values().stream().mapToInt(QueryResult::getRowCount).sum();
        String summary = defaultValue(plan.getSummary(), "已根据模板生成报表");
        String answer = buildAnswer(saved.fileName(), definition.getName(), summary, rowCount);
        // 只有成功生成后才记录取数计划与输入消耗，失败不会挤占会话预算
        templateStore.updatePlan(templateEntity, writePlan(plan));
        long totalChars = historyService.addInputChars(userId, conversation, promptChars);
        ChatResponse response = ChatResponse.builder()
                .conversationId(conversation)
                .status(AgentStatus.SUCCESS)
                .answer(answer)
                .reason("")
                .database(databaseKey)
                .databaseName(definition.getName())
                .sql(exposeSql(sqls))
                .rowCount(rowCount)
                .truncated(results.values().stream().anyMatch(QueryResult::isTruncated))
                .elapsedMs(System.currentTimeMillis() - start)
                .warnings(warnings)
                .timestamp(OffsetDateTime.now().format(TIMESTAMP_FORMAT))
                .template(toTemplateInfo(templateEntity))
                .promptChars(promptChars)
                .promptLimit(promptLimit)
                .inputChars(totalChars)
                .inputLimit(totalLimit)
                .report(ReportFileInfo.builder()
                        .fileId(saved.id())
                        .fileName(saved.fileName())
                        .downloadUrl("/api/report/files/" + saved.id())
                        .fileSize((long) output.length)
                        .rowCount(rowCount)
                        .summary(summary)
                        .build())
                .build();
        try {
            historyService.recordExchange(userId, conversation, question, databaseKey, response,
                    HistoryService.FEATURE_REPORT);
        } catch (RuntimeException e) {
            // 历史记录失败不影响报表下载
            log.warn("写入报表历史会话失败: conversation={}", conversation, e);
            response.getWarnings().add("报表已生成，但历史会话保存失败");
        }
        return response;
    }

    private List<ReportPlan.SqlSpec> validateSqlSpecs(ReportPlan plan) {
        List<ReportPlan.SqlSpec> sqls = plan.getSqls();
        if (sqls == null || sqls.isEmpty()) {
            throw new BadRequestException("模型未生成任何取数 SQL");
        }
        int maxSqlCount = properties.getReport().getMaxSqlCount() == null
                ? 5 : Math.max(1, properties.getReport().getMaxSqlCount());
        if (sqls.size() > maxSqlCount) {
            throw new BadRequestException("模型生成的 SQL 条数超过上限（" + maxSqlCount + "）");
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (ReportPlan.SqlSpec spec : sqls) {
            String alias = normalize(spec == null ? null : spec.getAlias());
            String sql = normalize(spec == null ? null : spec.getSql());
            if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
                throw new BadRequestException("报表 SQL 别名不合法: " + alias);
            }
            if (!aliases.add(alias.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("报表 SQL 别名重复: " + alias);
            }
            if (sql == null) {
                throw new BadRequestException("报表 SQL 为空: " + alias);
            }
            spec.setAlias(alias);
            spec.setSql(sql);
        }
        return sqls;
    }

    private Map<String, QueryResult> executeSqls(Long userId, String databaseKey,
                                                 List<ReportPlan.SqlSpec> sqls, ChatTask task) {
        Map<String, QueryResult> results = new LinkedHashMap<>();
        for (ReportPlan.SqlSpec spec : sqls) {
            // 每条 SQL 前先确认任务仍然有效，避免取消后继续占用数据库连接。
            throwIfCancelled(task);
            long start = System.currentTimeMillis();
            QueryResult result;
            try {
                // QueryExecutor 内部会再次用 SqlGuard 做只读校验，并登记语句级的取消动作
                result = queryExecutor.execute(userId, databaseKey, spec.getSql());
            } catch (ChatCancelledException e) {
                throw e;
            } catch (org.rin.db.UnsafeSqlException e) {
                log.warn("报表 SQL 未通过只读校验: alias={}, database={}, error={}",
                        spec.getAlias(), databaseKey, e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                // 必须打印完整异常与失败 SQL，否则真实的数据库错误会被包装后的提示掩盖。
                log.error("报表取数失败: alias={}, database={}, sql={}",
                        spec.getAlias(), databaseKey, spec.getSql(), e);
                throw new BadRequestException("报表取数失败（SQL 别名 " + spec.getAlias()
                        + "）：" + safeMessage(e));
            }
            if (result.isTooLarge()) {
                throw new BadRequestException("报表取数结果过大（SQL 别名 " + spec.getAlias()
                        + "），请缩小统计范围或增加过滤条件");
            }
            log.debug("报表 SQL 执行完成: alias={}, rows={}, elapsed={}ms", spec.getAlias(),
                    result.getRowCount(), System.currentTimeMillis() - start);
            results.put(spec.getAlias().toLowerCase(Locale.ROOT), result);
        }
        return results;
    }

    /**
     * 阶段检查点：任务已取消时立即中断，不再调用模型或执行 SQL。
     */
    private void throwIfCancelled(ChatTask task) {
        if (task != null) {
            task.throwIfCancelled();
        }
    }

    private String resolveDatabase(Long userId, String plannedDatabase,
                                   List<String> availableDatabases) {
        String planned = normalize(plannedDatabase);
        if (planned == null) {
            if (availableDatabases.size() == 1) {
                return availableDatabases.get(0);
            }
            throw new BadRequestException("模型未指定数据库，请重新生成或先选择数据源");
        }
        for (String key : availableDatabases) {
            if (key.equalsIgnoreCase(planned)) {
                return key;
            }
        }
        for (DataSourceDefinition definition : databaseRegistry.listForUser(userId)) {
            if (definition.getName() != null
                    && definition.getName().equalsIgnoreCase(planned)) {
                return definition.getKey();
            }
        }
        throw new BadRequestException("模型选择了当前用户无权访问的数据源: " + planned);
    }

    private void validateFile(MultipartFile file) {
        String name = sanitizeFileName(file.getOriginalFilename());
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                && !name.toLowerCase(Locale.ROOT).endsWith(".xls")) {
            throw new BadRequestException("仅支持 .xlsx 或 .xls 格式的 Excel 模板");
        }
        Long configured = properties.getReport().getMaxUploadBytes();
        long maxBytes = configured == null || configured <= 0
                ? 10L * 1024 * 1024 : configured;
        if (file.getSize() > maxBytes) {
            throw new BadRequestException("模板文件大小不能超过 "
                    + (maxBytes / 1024 / 1024) + "MB");
        }
    }

    private String sanitizeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        name = name.replaceAll("[<>:\"|?*]", "_");
        if (name.isEmpty()) {
            name = "report-template.xlsx";
        }
        if (name.length() > 120) {
            String ext = extension(name);
            name = name.substring(0, Math.max(1, 120 - ext.length())) + ext;
        }
        return name;
    }

    private String extension(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xls") ? ".xls" : ".xlsx";
    }


    private ReportFillPlan buildFillPlan(ExcelTemplate template, ReportPlan plan,
                                         Map<String, QueryResult> results,
                                         List<String> warnings) {
        Map<String, ExcelTemplate.Sheet> sheets = new HashMap<>();
        for (ExcelTemplate.Sheet sheet : template.getSheets()) {
            sheets.put(sheet.getName().toLowerCase(Locale.ROOT), sheet);
        }
        Set<String> warned = new LinkedHashSet<>();
        int maxTemplateRows = positive(properties.getReport().getMaxTemplateRows(), 200);
        int maxTemplateColumns = positive(properties.getReport().getMaxTemplateColumns(), 60);
        int maxFillRows = positive(properties.getReport().getMaxFillRows(), 5000);

        List<ReportFillPlan.CellValue> cells = new ArrayList<>();
        List<ReportPlan.CellMapping> cellMappings = plan.getCells() == null
                ? List.of() : plan.getCells();
        for (ReportPlan.CellMapping mapping : cellMappings) {
            if (mapping == null) {
                continue;
            }
            ExcelTemplate.Sheet sheet = requireSheet(sheets, mapping.getSheet());
            int row = requireCoordinate(mapping.getRow(), maxTemplateRows, "row", sheet.getName());
            int col = requireCoordinate(mapping.getCol(), maxTemplateColumns, "col", sheet.getName());
            validateCellWritable(sheet, row, col);
            QueryResult result = requireResult(results, mapping.getSource());
            int columnIndex = resolveColumn(result, mapping.getColumn());
            String value = "";
            if (result.getRows().isEmpty()) {
                warned.add("单元格 " + sheet.getName() + "!" + excelAddress(row, col) + " 的查询结果为空");
            } else {
                List<String> firstRow = result.getRows().get(0);
                value = columnIndex < firstRow.size() ? firstRow.get(columnIndex) : "";
                if (result.getRows().size() > 1) {
                    warned.add("单元格 " + sheet.getName() + "!" + excelAddress(row, col)
                            + " 的查询结果有多行，已取第一行");
                }
            }
            cells.add(ReportFillPlan.CellValue.builder()
                    .sheet(sheet.getName())
                    .row(row)
                    .col(col)
                    .value(value)
                    .build());
        }



        List<ReportFillPlan.TableValue> tables = new ArrayList<>();
        List<ReportPlan.TableMapping> tableMappings = plan.getTables() == null
                ? List.of() : plan.getTables();
        for (ReportPlan.TableMapping mapping : tableMappings) {
            if (mapping == null) {
                continue;
            }
            ExcelTemplate.Sheet sheet = requireSheet(sheets, mapping.getSheet());
            int startRow = requireCoordinate(mapping.getStartRow(), maxTemplateRows,
                    "startRow", sheet.getName());
            int startCol = requireCoordinate(mapping.getStartCol(), maxTemplateColumns,
                    "startCol", sheet.getName());
            validateCellWritable(sheet, startRow, startCol);
            Integer headerRow = null;
            if (mapping.getHeaderRow() != null) {
                headerRow = requireCoordinate(mapping.getHeaderRow(), maxTemplateRows,
                        "headerRow", sheet.getName());
                if (headerRow >= startRow) {
                    throw new BadRequestException("sheet[" + sheet.getName()
                            + "] 的 headerRow（" + headerRow
                            + "）必须小于表格数据起始行 startRow（" + startRow + "）");
                }
            }
            QueryResult result = requireResult(results, mapping.getSource());
            List<Integer> columnIndexes = new ArrayList<>();
            List<String> headers = new ArrayList<>();
            if (mapping.getColumns() == null || mapping.getColumns().isEmpty()) {
                for (int i = 0; i < result.getColumns().size(); i++) {
                    columnIndexes.add(i);
                    headers.add(result.getColumns().get(i));
                }
            } else {
                for (String column : mapping.getColumns()) {
                    int index = resolveColumn(result, column);
                    columnIndexes.add(index);
                    headers.add(result.getColumns().get(index));
                }
            }
            if (columnIndexes.isEmpty()) {
                throw new BadRequestException("表格映射未指定任何结果列：" + sheet.getName()
                        + " startRow=" + startRow);
            }
            if (startCol + columnIndexes.size() - 1 > maxTemplateColumns) {
                throw new BadRequestException("表格映射超出模板最大列数（" + maxTemplateColumns + "）");
            }
            if (headerRow != null) {
                for (int i = 0; i < columnIndexes.size(); i++) {
                    validateCellWritable(sheet, headerRow, startCol + i);
                }
            }

            int limit = maxFillRows;
            if (mapping.getMaxRows() != null && mapping.getMaxRows() > 0) {
                limit = Math.min(limit, mapping.getMaxRows());
            }
            int availableRows = result.getRows().size();
            int writeRows = Math.min(availableRows, limit);
            if (availableRows > writeRows) {
                warned.add("表格 " + sheet.getName() + "!" + excelAddress(startRow, startCol)
                        + " 仅写入前 " + writeRows + " 行，共 " + availableRows + " 行");
            }
            if (availableRows == 0) {
                warned.add("表格 " + sheet.getName() + "!" + excelAddress(startRow, startCol)
                        + " 的查询结果为空，未写入数据行");
            }
            List<List<String>> rows = new ArrayList<>(writeRows);
            for (int i = 0; i < writeRows; i++) {
                List<String> source = result.getRows().get(i);
                List<String> values = new ArrayList<>(columnIndexes.size());
                for (Integer columnIndex : columnIndexes) {
                    values.add(source != null && columnIndex < source.size()
                            ? source.get(columnIndex) : "");
                }
                rows.add(values);
            }
            tables.add(ReportFillPlan.TableValue.builder()
                    .sheet(sheet.getName())
                    .startRow(startRow)
                    .startCol(startCol)
                    .headers(headerRow == null ? null : headers)
                    .headerRow(headerRow)
                    .rows(rows)
                    .build());
        }
        warnings.addAll(warned);
        return ReportFillPlan.builder().cells(cells).tables(tables).build();
    }


    private ExcelTemplate.Sheet requireSheet(Map<String, ExcelTemplate.Sheet> sheets, String name) {
        String key = normalize(name);
        ExcelTemplate.Sheet sheet = key == null ? null : sheets.get(key.toLowerCase(Locale.ROOT));
        if (sheet == null) {
            throw new BadRequestException("报表映射引用了不存在的 sheet: " + name);
        }
        return sheet;
    }

    private int requireCoordinate(Integer value, int max, String field, String sheet) {
        if (value == null || value < 1 || value > max) {
            throw new BadRequestException("sheet[" + sheet + "] 的 " + field
                    + " 必须在 1~" + max + " 之间，实际为 " + value);
        }
        return value;
    }

    /**
     * 合并单元格只有左上角可写，其余位置写入会被 POI 丢弃，这里提前给出可读错误。
     */
    private void validateCellWritable(ExcelTemplate.Sheet sheet, int row, int col) {
        for (ExcelTemplate.Merged merged : sheet.getMergedRegions()) {
            if (row >= merged.getFirstRow() && row <= merged.getLastRow()
                    && col >= merged.getFirstColumn() && col <= merged.getLastColumn()) {
                if (row == merged.getFirstRow() && col == merged.getFirstColumn()) {
                    return;
                }
                throw new BadRequestException("sheet[" + sheet.getName() + "] 单元格 "
                        + excelAddress(row, col) + " 位于合并区域 " + merged.getRange()
                        + " 内，请改用左上角 " + excelAddress(merged.getFirstRow(),
                        merged.getFirstColumn()));
            }
        }
    }

    private QueryResult requireResult(Map<String, QueryResult> results, String source) {
        String alias = normalize(source);
        QueryResult result = alias == null ? null : results.get(alias.toLowerCase(Locale.ROOT));
        if (result == null) {
            throw new BadRequestException("报表映射引用了不存在的 SQL 别名: " + source);
        }
        return result;
    }

    /**
     * 支持按列名（忽略大小写）、1-based 列序号定位，便于模型输出时略有偏差也能容错。
     */
    private int resolveColumn(QueryResult result, String column) {
        String expected = normalize(column);
        if (expected == null) {
            if (result.getColumns().size() == 1) {
                return 0;
            }
            throw new BadRequestException("报表映射未指定列名，且 SQL 返回多列，可用列: "
                    + result.getColumns());
        }
        String unquoted = expected.replace("`", "").replace("\"", "").trim();
        for (int i = 0; i < result.getColumns().size(); i++) {
            if (result.getColumns().get(i).equalsIgnoreCase(unquoted)) {
                return i;
            }
        }
        if (unquoted.matches("\\d+")) {
            int index = Integer.parseInt(unquoted) - 1;
            if (index >= 0 && index < result.getColumns().size()) {
                return index;
            }
        }
        throw new BadRequestException("查询结果中不存在列 '" + column + "'，可用列: "
                + result.getColumns());
    }

    private SavedFile saveFile(Long userId, String conversationId, Long templateId,
                               String originalName, String extension, byte[] output) {
        Path root = storageRoot();
        String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = root.resolve(storedName).normalize();
        if (!target.startsWith(root)) {
            throw new BadRequestException("非法的报表存储路径");
        }
        try {
            Files.write(target, output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("写入报表文件失败: path={}", target, e);
            throw new BadRequestException("保存报表文件失败：" + safeMessage(e));
        }

        String displayName = displayName(originalName, extension);
        ReportFileEntity entity = new ReportFileEntity();
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setTemplateId(templateId);
        entity.setFileName(displayName);
        entity.setStoredName(storedName);
        entity.setContentType(contentType(extension));
        entity.setFileSize((long) output.length);
        entity.setStatus("READY");
        entity.setCreatedAt(LocalDateTime.now());
        try {
            fileMapper.insert(entity);
        } catch (RuntimeException e) {
            log.error("保存报表文件记录失败: userId={}, fileName={}", userId, displayName, e);
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupError) {
                // 清理失败不覆盖原始异常，但要留下日志便于排查残留文件
                log.warn("清理已写入的报表文件失败: path={}", target, cleanupError);
            }
            throw new BadRequestException("保存报表记录失败：" + safeMessage(e));
        }
        return new SavedFile(entity.getId(), displayName);
    }

    /**
     * 按当前用户读取报表文件元信息；不存在、无权限、物理文件缺失统一返回 404，避免泄露信息。
     */
    public ReportFileEntity requireFile(Long userId, Long fileId) {
        ReportFileEntity entity = fileMapper.selectById(fileId);
        if (entity == null || entity.getUserId() == null || !entity.getUserId().equals(userId)) {
            throw new NotFoundException("报表文件不存在或已过期");
        }
        Path path = resolveStoredPath(entity);
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("报表文件不存在或已过期");
        }
        return entity;
    }

    public Path resolveStoredPath(ReportFileEntity entity) {
        if (!StringUtils.hasText(entity.getStoredName())) {
            throw new NotFoundException("报表文件不存在或已过期");
        }
        Path root = storageRoot();
        Path path = root.resolve(entity.getStoredName()).normalize();
        if (!path.startsWith(root)) {
            throw new NotFoundException("报表文件不存在或已过期");
        }
        return path;
    }

    private Path storageRoot() {
        String configured = properties.getReport().getStorageDir();
        Path root = Paths.get(StringUtils.hasText(configured) ? configured : "./data/reports")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.error("无法创建报表存储目录: {}", root, e);
            throw new IllegalStateException("无法创建报表存储目录: " + root, e);
        }
        return root;
    }

    private String displayName(String originalName, String extension) {
        String base = originalName.substring(0, originalName.length() - extension.length());
        if (base.isBlank()) {
            base = "报表";
        }
        if (!base.endsWith("报表")) {
            base = base + "_报表";
        }
        String name = base + "_" + LocalDateTime.now().format(FILE_TIMESTAMP) + extension;
        return name.length() > 200
                ? name.substring(0, 200 - extension.length()) + extension
                : name;
    }

    static String contentType(String extension) {
        return ".xls".equalsIgnoreCase(extension) ? XLS_CONTENT_TYPE : XLSX_CONTENT_TYPE;
    }

    private String buildAnswer(String fileName, String databaseName, String summary,
                               int rowCount) {
        return "### 报表已生成\n\n"
                + "- 文件：" + fileName + "\n"
                + "- 数据源：" + databaseName + "\n"
                + "- 返回数据：" + rowCount + " 行\n\n"
                + summary + "\n\n请点击下方链接下载 Excel 文件。";
    }

    private String exposeSql(List<ReportPlan.SqlSpec> sqls) {
        if (Boolean.FALSE.equals(properties.getSql().getExposeSql())) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (ReportPlan.SqlSpec spec : sqls) {
            if (builder.length() > 0) {
                builder.append(";\n\n");
            }
            builder.append("-- ").append(spec.getAlias()).append('\n').append(spec.getSql());
        }
        return builder.toString();
    }

    /**
     * 智能报表会话 ID：缺省时生成 rep- 前缀的会话 ID，便于与数据问答会话区分。
     */
    private String resolveReportConversationId(String conversationId) {
        String normalized = normalize(conversationId);
        if (normalized == null) {
            return "rep-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        }
        validateConversationId(normalized);
        return normalized;
    }

    private void validateConversationId(String conversationId) {
        if (!CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            throw new BadRequestException("会话 ID 不合法，仅支持字母、数字、下划线和短横线（最长 64 位）");
        }
    }

    /**
     * 按 ID 读取模板并校验归属；不存在或非本人模板统一返回 404。
     */
    public ReportTemplateEntity requireTemplate(Long userId, Long templateId) {
        ReportTemplateEntity entity = templateStore.findById(userId, templateId);
        if (entity == null) {
            throw new NotFoundException("报表模板不存在或无权访问");
        }
        return entity;
    }

    /**
     * 当前用户导入过的模板（用于“我的模板”列表与重新下载）。
     */
    public List<ReportTemplateInfo> listTemplates(Long userId) {
        List<ReportTemplateInfo> result = new ArrayList<>();
        for (ReportTemplateEntity entity : templateStore.listByUser(userId)) {
            result.add(toTemplateInfo(entity));
        }
        return result;
    }

    public Path resolveTemplatePath(ReportTemplateEntity entity) {
        return templateStore.resolvePath(entity);
    }

    private ReportTemplateInfo toTemplateInfo(ReportTemplateEntity entity) {
        if (entity == null) {
            return null;
        }
        return ReportTemplateInfo.builder()
                .templateId(entity.getId())
                .conversationId(entity.getConversationId())
                .templateName(entity.getTemplateName())
                .downloadUrl("/api/report/templates/" + entity.getId())
                .fileSize(entity.getFileSize())
                .contentType(entity.getContentType())
                .sheetSummary(entity.getSheetSummary())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * 模板结构摘要，例如 “客户统计(20行×6列); 汇总(8行×4列)”。
     */
    private String summarizeTemplate(ExcelTemplate template) {
        if (template == null || template.getSheets() == null || template.getSheets().isEmpty()) {
            return "模板无可识别内容";
        }
        StringBuilder builder = new StringBuilder();
        for (ExcelTemplate.Sheet sheet : template.getSheets()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(sheet.getName())
                    .append('(').append(sheet.getLastRow()).append("行×")
                    .append(sheet.getLastColumn()).append("列)");
            if (builder.length() > 900) {
                builder.append(" ...");
                break;
            }
        }
        return builder.length() > 1024 ? builder.substring(0, 1024) : builder.toString();
    }

    /**
     * 模板上传后的助手消息：说明模板已保存、结构摘要，以及后续可以如何调整。
     */
    private String buildTemplateAnswer(ReportTemplateEntity entity) {
        return "### 模板已导入\n\n"
                + "- 模板：" + entity.getTemplateName() + "\n"
                + "- 结构：" + defaultValue(entity.getSheetSummary(), "未识别到工作表") + "\n\n"
                + "请直接描述报表需求（例如统计口径、时间范围、分组维度），"
                + "生成后可继续在本会话里要求调整取数逻辑与报表内容。\n\n"
                + "当前会话只支持一个模板；如需更换模板，请新建报表会话后重新上传。";
    }

    /**
     * 报表会话的历史上下文：只保留最近几轮的问题与答案摘要，避免提示词无限增长。
     */
    private String buildReportHistory(Long userId, String conversationId) {
        Integer configured = properties.getReport().getHistoryExchanges();
        int limit = configured == null || configured <= 0
                ? 4 : Math.min(configured, positive(properties.getMemory().getMaxExchanges(), 6));
        try {
            List<HistoryExchange> exchanges =
                    historyService.recentExchanges(userId, conversationId, limit);
            StringBuilder builder = new StringBuilder();
            for (HistoryExchange exchange : exchanges) {
                builder.append("用户: ").append(truncateText(exchange.question(), 300)).append('\n');
                String answer = exchange.answer() == null ? "" : exchange.answer().trim();
                if (!answer.isEmpty()) {
                    builder.append("助手: ").append(truncateText(answer, 400)).append('\n');
                }
                builder.append('\n');
            }
            return builder.toString().trim();
        } catch (RuntimeException e) {
            log.debug("恢复报表历史上下文失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 序列化本轮取数计划，供下一轮调整时作为上下文。
     */
    private String writePlan(ReportPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            log.warn("序列化报表取数计划失败: {}", e.getMessage());
            return null;
        }
    }

    private int maxPromptChars() {
        return positive(properties.getReport().getMaxPromptChars(), 30_000);
    }

    private static String truncateText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "...";
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String defaultValue(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }

    private String excelAddress(int row, int col) {
        StringBuilder letters = new StringBuilder();
        int current = col;
        while (current > 0) {
            int remainder = (current - 1) % 26;
            letters.insert(0, (char) ('A' + remainder));
            current = (current - 1) / 26;
        }
        return letters.append(row).toString();
    }

    private record SavedFile(Long id, String fileName) {
    }
}
