package org.rin.task;

import org.rin.exception.ChatCancelledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次数据问答 / 智能报表任务的取消句柄。
 *
 * <p>用户点击“停止”、关闭页面导致 SSE 断开、SSE 推送失败或连接超时时，
 * {@link #cancel()} 会把任务标记为已取消，并做两件事：</p>
 * <ul>
 *     <li>中断正在执行的工作线程，让阻塞在模型流式读取上的调用立即返回；</li>
 *     <li>执行注册的中断动作，例如 {@code Statement#cancel()}，尽快释放数据库连接。</li>
 * </ul>
 *
 * <p>绑定到工作线程（{@link #bind()}）后，下游组件可以通过 {@link #currentOrNull()}
 * 拿到当前任务，不必把句柄层层透传。</p>
 */
public final class ChatTask {

    private static final Logger log = LoggerFactory.getLogger(ChatTask.class);

    /** 等待模型流结束时的取消轮询间隔：兼顾响应速度与空转开销。 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
     * 当前线程正在执行的任务。放在 ThreadLocal 里，让 {@code QueryExecutor} 之类的
     * 底层组件也能感知取消，而无需修改对外方法签名。
     */
    private static final ThreadLocal<ChatTask> CURRENT = new ThreadLocal<>();

    private final long userId;
    private final String conversationId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final List<Runnable> abortActions = new CopyOnWriteArrayList<>();
    private volatile Thread worker;

    ChatTask(long userId, String conversationId) {
        this.userId = userId;
        this.conversationId = conversationId;
    }

    public long getUserId() {
        return userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean isFinished() {
        return finished.get();
    }

    /**
     * 绑定到当前线程：取消时能中断该线程，并让下游组件通过 {@link #currentOrNull()} 感知。
     */
    public void bind() {
        Thread current = Thread.currentThread();
        this.worker = current;
        CURRENT.set(this);
        if (cancelled.get()) {
            current.interrupt();
        }
    }

    /**
     * 解除线程绑定。
     *
     * <p>取消时留下的中断标记必须清掉，否则线程池复用该线程时，
     * 下一次等待/IO 会立刻抛中断异常。</p>
     */
    public void unbind() {
        if (worker == Thread.currentThread()) {
            worker = null;
        }
        CURRENT.remove();
        if (cancelled.get()) {
            Thread.interrupted();
        }
    }

    /**
     * 当前线程正在执行的任务；没有绑定时返回 {@code null}。
     */
    public static ChatTask currentOrNull() {
        return CURRENT.get();
    }

    /**
     * 取消任务（幂等）。
     *
     * @return true 表示本次调用真正取消了任务；已完成或已取消的任务返回 false
     */
    public boolean cancel() {
        if (finished.get() || !cancelled.compareAndSet(false, true)) {
            return false;
        }
        interruptWorker();
        for (Runnable action : abortActions) {
            runQuietly(action);
        }
        abortActions.clear();
        return true;
    }

    /**
     * 标记任务正常结束。之后到达的取消请求（例如 SSE 完成回调触发的清理）不再中断线程。
     */
    public boolean finish() {
        return finished.compareAndSet(false, true);
    }

    /**
     * 注册取消动作；任务已取消时立即执行，保证“取消后才发现”的竞态也能生效。
     */
    public void onCancel(Runnable action) {
        if (action == null) {
            return;
        }
        if (cancelled.get()) {
            runQuietly(action);
            return;
        }
        abortActions.add(action);
        if (cancelled.get() && abortActions.remove(action)) {
            runQuietly(action);
        }
    }

    /**
     * 任务已取消时抛出 {@link ChatCancelledException}，用于各阶段之间的检查点。
     */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new ChatCancelledException();
        }
    }

    /**
     * 等待模型流结束，期间每 {@value #POLL_INTERVAL_MILLIS}ms 检查一次取消状态。
     *
     * @return true 表示已结束；false 表示等待超时
     * @throws ChatCancelledException 任务已被取消（含等待期间被中断）
     * @throws InterruptedException    线程被外部中断且任务未取消，由调用方决定如何处理
     */
    public boolean awaitCancellable(CountDownLatch latch, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            throwIfCancelled();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throwIfCancelled();
                return latch.getCount() == 0;
            }
            long slice = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MILLIS));
            try {
                if (latch.await(slice, TimeUnit.NANOSECONDS)) {
                    throwIfCancelled();
                    return true;
                }
            } catch (InterruptedException e) {
                if (cancelled.get()) {
                    // 取消时中断线程只是为了尽快结束等待，不向外传播中断标记。
                    throw new ChatCancelledException();
                }
                throw e;
            }
        }
    }

    private void interruptWorker() {
        Thread target = worker;
        if (target != null && target != Thread.currentThread()) {
            target.interrupt();
        }
    }

    private void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.debug("执行取消动作失败: {}", e.getMessage());
        }
    }
}
