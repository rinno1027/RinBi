package org.rin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.rin.agent.dto.ChatRequest;
import org.rin.agent.dto.ChatResponse;
import org.rin.agent.dto.ChatStreamError;
import org.rin.agent.dto.DatabaseInfo;
import org.rin.auth.AuthService;
import org.rin.auth.AuthUser;
import org.rin.db.DataSourceDefinition;
import org.rin.db.DatabaseRegistry;
import org.rin.exception.BadRequestException;
import org.rin.exception.ChatCancelledException;
import org.rin.task.ChatTask;
import org.rin.task.ChatTaskRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Agent HTTP 接口。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * 整个流程包含“生成计划 + 执行 SQL + 流式整理”，给 SSE 连接留足时间。
     */
    private static final long STREAM_TIMEOUT_MILLIS = 600_000L;

    @Autowired
    private AgentService agentService;

    @Autowired
    private DatabaseRegistry databaseRegistry;

    @Autowired
    private ConversationMemory conversationMemory;

    @Autowired
    private ChatTaskRegistry chatTaskRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("agentStreamExecutor")
    private ExecutorService streamExecutor;

    /**
     * 流式对话。事件顺序：
     * <ol>
     *   <li>{@code meta}：conversationId、数据源、SQL、结构化结果（answer 为空）</li>
     *   <li>{@code reasoning}：模型思考过程分片，JSON 字符串</li>
     *   <li>{@code token}：最终答案分片，JSON 字符串（边生成边渲染）</li>
     *   <li>{@code done}：完整 ChatResponse（answer 为最终文本，失败/不可行也通过该事件返回）</li>
     * </ol>
     * 未预期异常通过 {@code error} 事件返回。
     */
    @PostMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
                           @RequestBody ChatRequest request,
                           HttpServletResponse response) {
        // 先同步校验，参数错误仍由 GlobalExceptionHandler 返回 400 JSON。
        ChatRequest prepared = agentService.prepare(user.id(), request);
        long userId = user.id();

        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        // 一次 SSE 对话对应一个可取消任务：用户点击停止、关闭页面导致连接断开、
        // 推送失败或连接超时都会取消它，避免继续消耗模型 token 与数据库连接。
        ChatTask task = chatTaskRegistry.start(userId, prepared.getConversationId());

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时: conversation={}", prepared.getConversationId());
            chatTaskRegistry.cancel(task);
            completeQuietly(emitter);
        });
        emitter.onError(error -> {
            log.debug("SSE 连接异常: conversation={}, error={}",
                    prepared.getConversationId(), safeMessage(error));
            chatTaskRegistry.cancel(task);
        });

        try {
            streamExecutor.execute(() -> streamChat(userId, prepared, emitter, task));
        } catch (RuntimeException e) {
            // 线程池已拒绝（队列与线程都打满）：任务不会执行，必须立即释放注册表句柄。
            chatTaskRegistry.finish(task);
            throw e;
        }
        return emitter;
    }

    /**
     * 在线程池中执行一次流式对话。
     *
     * <p>任务结束（正常完成、被取消或异常）时一定会解除线程绑定、从注册表移除并关闭 SSE。</p>
     */
    private void streamChat(long userId, ChatRequest prepared, SseEmitter emitter, ChatTask task) {
        task.bind();
        try {
            ChatResponse result = agentService.execute(userId, prepared, new ChatStreamListener() {
                @Override
                public void onMeta(ChatResponse meta) {
                    sendOrCancel(emitter, task, "meta", meta);
                }

                @Override
                public void onReasoning(String chunk) {
                    sendOrCancel(emitter, task, "reasoning", chunk);
                }

                @Override
                public void onToken(String chunk) {
                    sendOrCancel(emitter, task, "token", chunk);
                }
            }, task);
            sendEvent(emitter, "done", result);
        } catch (ChatCancelledException e) {
            log.info("对话已停止: conversation={}", prepared.getConversationId());
            // 主动停止时通知前端保留已生成的部分内容；连接已断开时该事件自然发送失败。
            sendEvent(emitter, "stopped", ChatStreamError.builder()
                    .code("STOPPED")
                    .message("已停止生成")
                    .status(0)
                    .build());
        } catch (RuntimeException e) {
            log.warn("流式对话失败: conversation={}, error={}",
                    prepared.getConversationId(), safeMessage(e));
            sendEvent(emitter, "error", ChatStreamError.builder()
                    .code("ERROR")
                    .message("处理请求时发生错误，请稍后重试")
                    .status(500)
                    .build());
        } finally {
            task.unbind();
            chatTaskRegistry.finish(task);
            completeQuietly(emitter);
        }
    }

    /**
     * SSE 推送失败说明浏览器已断开：立即取消任务，停止后续的模型调用与 SQL 执行。
     */
    private void sendOrCancel(SseEmitter emitter, ChatTask task, String name, Object data) {
        if (!sendEvent(emitter, name, data)) {
            chatTaskRegistry.cancel(task);
        }
    }

    /**
     * 关闭 SSE 连接。连接已被容器关闭时忽略重复关闭的异常。
     */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE 连接已关闭: {}", safeMessage(e));
        }
    }

    /**
     * SSE data 统一写成 JSON 字符串，避免文本分片中的换行破坏事件格式。
     */
    private boolean sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(objectMapper.writeValueAsString(data)));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 推送失败（客户端可能已断开）: {}", safeMessage(e));
            return false;
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }

    @GetMapping("/databases")
    public List<DatabaseInfo> databases(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user) {
        List<DatabaseInfo> result = new ArrayList<>();
        for (DataSourceDefinition database : databaseRegistry.listForUser(user.id())) {
            result.add(DatabaseInfo.builder()
                    .key(database.getKey())
                    .name(database.getName() == null ? database.getKey() : database.getName())
                    .description(database.getDescription())
                    .dialect(database.getDialect())
                    .build());
        }
        return result;
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> clearConversation(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @PathVariable String conversationId) {
        if (conversationId == null || conversationId.length() > 64) {
            throw new BadRequestException("conversationId 不合法");
        }
        conversationMemory.clear(ConversationMemory.memoryKey(user.id(), conversationId));
        return ResponseEntity.noContent().build();
    }

    /**
     * 主动停止一次正在执行的数据问答 / 智能报表。
     *
     * <p>前端点击“停止”按钮或关闭页面时调用：后端会中断模型流式调用与数据库查询，
     * 并关闭对应的 SSE 连接，已生成的部分内容仍保留在前端。接口幂等：
     * 没有正在执行的任务时同样返回 204。</p>
     */
    @PostMapping("/conversations/{conversationId}/stop")
    public ResponseEntity<Void> stop(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @PathVariable String conversationId) {
        if (conversationId == null || conversationId.length() > 64) {
            throw new BadRequestException("conversationId 不合法");
        }
        boolean cancelled = chatTaskRegistry.cancel(user.id(), conversationId);
        log.info("停止任务: user={}, conversation={}, cancelled={}",
                user.id(), conversationId, cancelled);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    public Map<String, Object> health(HttpServletRequest request) {
        // health 不强制登录：未登录时只返回存活状态，避免泄露数据源信息。
        AuthUser user = (AuthUser) request.getAttribute(AuthService.AUTH_USER_ATTRIBUTE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("databases", user == null ? List.of() : databaseRegistry.getKeys(user.id()));
//        result.put("conversations", conversationMemory.size());
        result.put("timestamp", OffsetDateTime.now().format(TIMESTAMP_FORMAT));
        return result;
    }
}
