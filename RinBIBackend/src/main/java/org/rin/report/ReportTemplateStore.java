package org.rin.report;

import org.rin.config.AgentProperties;
import org.rin.exception.BadRequestException;
import org.rin.exception.NotFoundException;
import org.rin.report.entity.ReportTemplateEntity;
import org.rin.report.mapper.ReportTemplateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 智能报表模板的落盘与元信息持久化。
 *
 * <p>模板文件与生成的报表文件保存在同一个根目录下的 {@code templates} 子目录，
 * 一个会话（{@code conversationId}）只允许存在一个模板；删除会话时一并清理
 * 模板记录与物理文件。这里不依赖 HistoryService，避免与 ReportService 形成循环依赖。</p>
 */
@Component
public class ReportTemplateStore {

    private static final Logger log = LoggerFactory.getLogger(ReportTemplateStore.class);
    private static final String TEMPLATE_DIR = "templates";
    private static final int DEFAULT_LIST_LIMIT = 50;

    @Autowired
    private ReportTemplateMapper templateMapper;

    @Autowired
    private AgentProperties properties;

    /**
     * 保存模板文件并写入元信息记录。
     */
    public ReportTemplateEntity save(ReportTemplateEntity entity, byte[] content) {
        Path root = templateRoot();
        String extension = extension(entity.getTemplateName());
        String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = root.resolve(storedName).normalize();
        if (!target.startsWith(root)) {
            throw new BadRequestException("非法的模板存储路径");
        }
        try {
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("写入报表模板文件失败: path={}", target, e);
            throw new BadRequestException("保存模板文件失败：" + e.getMessage());
        }

        entity.setStoredName(storedName);
        entity.setContentType(ReportService.contentType(extension));
        entity.setFileSize((long) content.length);
        entity.setStatus("READY");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        try {
            templateMapper.insert(entity);
        } catch (RuntimeException e) {
            log.error("保存报表模板记录失败: userId={}, conversation={}, name={}",
                    entity.getUserId(), entity.getConversationId(), entity.getTemplateName(), e);
            deleteQuietly(target);
            throw new BadRequestException("保存模板记录失败：" + e.getMessage());
        }
        log.info("报表模板已保存: user={}, conversation={}, templateId={}, name={}",
                entity.getUserId(), entity.getConversationId(), entity.getId(),
                entity.getTemplateName());
        return entity;
    }

    public ReportTemplateEntity findByConversation(Long userId, String conversationId) {
        if (userId == null || !StringUtils.hasText(conversationId)) {
            return null;
        }
        return templateMapper.selectByConversation(userId, conversationId);
    }

    public ReportTemplateEntity findById(Long userId, Long templateId) {
        if (userId == null || templateId == null) {
            return null;
        }
        ReportTemplateEntity entity = templateMapper.selectById(templateId);
        if (entity == null || entity.getUserId() == null || !entity.getUserId().equals(userId)) {
            return null;
        }
        return entity;
    }

    /**
     * 当前用户导入过的模板，按创建时间倒序。
     */
    public List<ReportTemplateEntity> listByUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return templateMapper.selectByUser(userId, DEFAULT_LIST_LIMIT);
    }

    /**
     * 读取模板文件内容；文件缺失时返回 404 语义的错误。
     */
    public byte[] read(ReportTemplateEntity entity) {
        Path path = resolvePath(entity);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("读取报表模板文件失败: templateId={}, path={}",
                    entity.getId(), path, e);
            throw new NotFoundException("模板文件不存在或已过期，请重新上传");
        }
    }

    public Path resolvePath(ReportTemplateEntity entity) {
        if (entity == null || !StringUtils.hasText(entity.getStoredName())) {
            throw new NotFoundException("模板文件不存在或已过期");
        }
        Path root = templateRoot();
        Path path = root.resolve(entity.getStoredName()).normalize();
        if (!path.startsWith(root)) {
            throw new NotFoundException("模板文件不存在或已过期");
        }
        return path;
    }

    /**
     * 记录最近一次成功生效的取数计划，供下一轮调整时作为上下文。
     */
    public void updatePlan(ReportTemplateEntity entity, String planJson) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        try {
            ReportTemplateEntity update = new ReportTemplateEntity();
            update.setId(entity.getId());
            update.setPlanJson(planJson);
            update.setUpdatedAt(LocalDateTime.now());
            templateMapper.updateById(update);
            entity.setPlanJson(planJson);
        } catch (RuntimeException e) {
            // 计划只是上下文，落库失败不影响本次报表生成
            log.warn("保存报表取数计划失败: templateId={}", entity.getId(), e);
        }
    }

    /**
     * 删除会话绑定的模板记录与物理文件；不存在时静默返回。
     */
    public void deleteByConversation(Long userId, String conversationId) {
        ReportTemplateEntity entity = findByConversation(userId, conversationId);
        if (entity == null) {
            return;
        }
        try {
            templateMapper.deleteById(entity.getId());
        } catch (RuntimeException e) {
            log.warn("删除报表模板记录失败: templateId={}", entity.getId(), e);
            return;
        }
        try {
            Path path = resolvePath(entity);
            Files.deleteIfExists(path);
        } catch (NotFoundException e) {
            log.debug("模板文件已不存在，跳过清理: templateId={}", entity.getId());
        } catch (IOException e) {
            log.warn("删除报表模板文件失败: templateId={}, error={}",
                    entity.getId(), e.getMessage());
        }
    }

    private Path templateRoot() {
        String configured = properties.getReport().getStorageDir();
        Path root = Paths.get(StringUtils.hasText(configured) ? configured : "./data/reports")
                .toAbsolutePath().normalize()
                .resolve(TEMPLATE_DIR);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.error("无法创建报表模板存储目录: {}", root, e);
            throw new IllegalStateException("无法创建报表模板存储目录: " + root, e);
        }
        return root;
    }

    private static String extension(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xls") ? ".xls" : ".xlsx";
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupError) {
            log.warn("清理已写入的模板文件失败: path={}", path, cleanupError);
        }
    }
}
