package org.rin.report;

import org.rin.agent.dto.ChatResponse;
import org.rin.auth.AuthService;
import org.rin.auth.AuthUser;
import org.rin.report.dto.ReportChatRequest;
import org.rin.report.dto.ReportTemplateInfo;
import org.rin.report.entity.ReportFileEntity;
import org.rin.report.entity.ReportTemplateEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 智能报表接口：导入模板、在报表会话中多轮调整、下载模板与生成的文件。
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    @Autowired
    private ReportService reportService;

    /**
     * 导入 Excel 模板：模板文件保存到服务器，并创建独立的报表会话。
     *
     * <p>一个 {@code conversationId} 只支持一个模板；重复导入会返回 400。
     * 响应中的 {@code templateId}、{@code downloadUrl} 可用于后续调整与下载。</p>
     */
    @PostMapping(value = "/templates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReportTemplateInfo uploadTemplate(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        return reportService.uploadTemplate(user.id(), file, conversationId);
    }

    /**
     * 当前用户导入过的模板列表，便于重新下载。
     */
    @GetMapping("/templates")
    public List<ReportTemplateInfo> templates(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user) {
        return reportService.listTemplates(user.id());
    }

    /**
     * 下载用户自己导入的模板；仅模板所属用户可访问。
     */
    @GetMapping("/templates/{templateId}")
    public ResponseEntity<Resource> downloadTemplate(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @PathVariable Long templateId) {
        ReportTemplateEntity entity = reportService.requireTemplate(user.id(), templateId);
        Path path = reportService.resolveTemplatePath(entity);
        Resource resource = new FileSystemResource(path);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(entity.getTemplateName(), StandardCharsets.UTF_8)
                .build();
        long length = entity.getFileSize() == null ? -1 : entity.getFileSize();
        log.debug("下载报表模板: user={}, templateId={}, name={}", user.id(), templateId,
                entity.getTemplateName());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReportService.contentType(
                        entity.getTemplateName() == null ? "" : entity.getTemplateName())))
                .contentLength(length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    /**
     * 在报表会话中生成或调整报表：复用会话绑定的模板，按本轮需求重新规划取数逻辑。
     *
     * <p>累计输入超过 {@code agent.report.max-conversation-chars} 时返回
     * {@code 400 / INPUT_LIMIT}，前端应引导用户新建会话并重新上传模板。</p>
     */
    @PostMapping("/chat")
    public ChatResponse chat(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @RequestBody ReportChatRequest request) {
        return reportService.chat(user.id(), request);
    }

    /**
     * 一次性上传模板并生成报表（兼容旧前端）；响应结构与 /api/agent/chat 的 done 事件一致。
     *
     * <p>该接口等价于先调用 {@code POST /templates} 再调用 {@code POST /chat}：
     * 模板会被保存并创建报表会话，之后可以在同一会话中继续调整报表内容。</p>
     *
     * <p>{@code ruleIds} 为当前用户勾选的统计规则，仅允许在指定 {@code database} 时使用；
     * 规则会以 JSON 注入报表规划提示词，不参与 POI 回填。</p>
     */
    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatResponse generate(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "database", required = false) String database,
            @RequestParam(value = "requirement", required = false) String requirement,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "ruleIds", required = false) List<Long> ruleIds) {
        return reportService.generate(user.id(), file, database, requirement, conversationId,
                ruleIds);
    }

    /**
     * 下载报表文件；仅文件所属用户可访问。
     */
    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> download(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @PathVariable Long fileId) {
        ReportFileEntity entity = reportService.requireFile(user.id(), fileId);
        Path path = reportService.resolveStoredPath(entity);
        Resource resource = new FileSystemResource(path);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(entity.getFileName(), StandardCharsets.UTF_8)
                .build();
        long length = entity.getFileSize() == null ? -1 : entity.getFileSize();
        log.debug("下载报表文件: user={}, fileId={}, name={}", user.id(), fileId,
                entity.getFileName());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReportService.contentType(
                        entity.getFileName() == null ? "" : entity.getFileName())))
                .contentLength(length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }
}
