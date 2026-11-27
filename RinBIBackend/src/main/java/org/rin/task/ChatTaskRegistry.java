package org.rin.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正在执行的数据问答 / 智能报表任务注册表。
 *
 * <p>以“用户 + 会话”为键登记任务，这样前端只需要带上 {@code conversationId}
 * 就能通过停止接口找到并取消对应任务，而不必额外维护请求 id。</p>
 *
 * <p>任务必须在执行结束时调用 {@link #finish(ChatTask)}，否则注册表会一直持有句柄；
 * 控制器与服务层都使用 {@code try/finally} 保证这一点。</p>
 */
@Component
public class ChatTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatTaskRegistry.class);

    private final Map<String, ChatTask> tasks = new ConcurrentHashMap<>();

    /**
     * 开始一次任务。同一会话已有未结束的任务时，先取消旧任务，
     * 避免两个流并行写同一会话、重复消耗模型与数据库资源。
     */
    public ChatTask start(long userId, String conversationId) {
        ChatTask task = new ChatTask(userId, conversationId);
        ChatTask previous = tasks.put(key(userId, conversationId), task);
        if (previous != null && previous != task) {
            log.info("会话存在未结束的任务，取消旧任务: user={}, conversation={}",
                    userId, conversationId);
            previous.cancel();
        }
        return task;
    }

    /**
     * 任务结束：标记完成并从注册表移除。
     */
    public void finish(ChatTask task) {
        if (task == null) {
            return;
        }
        task.finish();
        tasks.remove(key(task.getUserId(), task.getConversationId()), task);
    }

    /**
     * 主动停止某个会话正在执行的任务。
     *
     * @return true 表示确实取消了一个正在执行的任务
     */
    public boolean cancel(long userId, String conversationId) {
        if (conversationId == null) {
            return false;
        }
        ChatTask task = tasks.get(key(userId, conversationId));
        return task != null && task.cancel();
    }

    /**
     * 直接取消指定任务（例如 SSE 推送失败、连接超时时调用）。
     *
     * @return true 表示本次调用真正取消了任务
     */
    public boolean cancel(ChatTask task) {
        return task != null && task.cancel();
    }

    /**
     * 当前正在执行的任务数，用于健康检查与排障。
     */
    public int size() {
        return tasks.size();
    }

    private String key(long userId, String conversationId) {
        return userId + ":" + (conversationId == null ? "" : conversationId);
    }
}
