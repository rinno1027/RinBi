package org.rin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式对话专用线程池。
 *
 * <p>SSE 请求返回 {@code SseEmitter} 后仍需等待“生成计划 + 执行 SQL + 模型流式输出”，
 * 这部分工作放在独立线程池中执行，避免长期占用 Tomcat 请求线程。</p>
 *
 * <p>线程池参数全部来自 {@code agent.stream.*}，未配置时使用本类中的默认值。</p>
 */
@Configuration
public class StreamConfig {

    private static final int DEFAULT_CORE_POOL_SIZE = 8;
    private static final int DEFAULT_MAX_POOL_SIZE = 32;
    private static final int DEFAULT_QUEUE_CAPACITY = 100;
    private static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;
    private static final String DEFAULT_THREAD_NAME_PREFIX = "agent-stream-";

    @Bean(name = "agentStreamExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor agentStreamExecutor(AgentProperties properties) {
        AgentProperties.Stream stream = properties.getStream();

        int corePoolSize = resolvePositive(stream.getCorePoolSize(), DEFAULT_CORE_POOL_SIZE);
        // 最大线程数必须不小于核心线程数，否则 ThreadPoolExecutor 构造会抛异常
        int maxPoolSize = Math.max(corePoolSize,
                resolvePositive(stream.getMaxPoolSize(), DEFAULT_MAX_POOL_SIZE));
        long keepAliveSeconds = resolveKeepAliveSeconds(stream.getKeepAliveSeconds());
        BlockingQueue<Runnable> workQueue = buildQueue(stream.getQueueCapacity());
        ThreadFactory threadFactory = buildThreadFactory(stream);

        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                workQueue,
                threadFactory,
                // 队列与线程都打满时直接拒绝，由调用方感知（SSE 连接会收到错误/断开），
                // 不使用 CallerRunsPolicy，避免长耗时流式任务回落到 Tomcat 请求线程。
                new ThreadPoolExecutor.AbortPolicy());
    }

    private BlockingQueue<Runnable> buildQueue(Integer queueCapacity) {
        int capacity = queueCapacity == null ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
        return capacity > 0 ? new LinkedBlockingQueue<>(capacity) : new SynchronousQueue<>();
    }

    private ThreadFactory buildThreadFactory(AgentProperties.Stream stream) {
        String prefix = StringUtils.hasText(stream.getThreadNamePrefix())
                ? stream.getThreadNamePrefix() : DEFAULT_THREAD_NAME_PREFIX;
        boolean daemon = !Boolean.FALSE.equals(stream.getDaemon());
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(daemon);
            return thread;
        };
    }

    private int resolvePositive(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private long resolveKeepAliveSeconds(Long value) {
        return value == null || value < 0 ? DEFAULT_KEEP_ALIVE_SECONDS : value;
    }
}

