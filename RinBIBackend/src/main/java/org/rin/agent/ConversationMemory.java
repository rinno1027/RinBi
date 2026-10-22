package org.rin.agent;

import org.rin.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 进程内会话记忆：每个 conversationId 保留最近 N 轮问答，空闲超过 TTL 自动清理。
 *
 * <p>生产环境如需多实例部署，可将本类替换为 Redis 实现；接口保持简单，便于替换。</p>
 */
@Service
public class ConversationMemory {

    @Autowired
    private AgentProperties properties;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleaner;

    @PostConstruct
    public void startCleaner() {
        cleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-memory-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        cleaner.scheduleWithFixedDelay(this::cleanupExpired, 5, 5, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stopCleaner() {
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
        sessions.clear();
    }

    public String createConversationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 记忆按用户隔离，避免不同用户使用同一 conversationId 时读到彼此上下文。
     */
    public static String memoryKey(Long userId, String conversationId) {
        return (userId == null ? "anonymous" : userId) + ":" + conversationId;
    }

    public boolean exists(String conversationId) {
        return conversationId != null && sessions.containsKey(conversationId);
    }

    public List<Exchange> getHistory(String conversationId) {
        if (conversationId == null) {
            return new ArrayList<>();
        }
        Session session = sessions.get(conversationId);
        if (session == null) {
            return new ArrayList<>();
        }
        session.touch();
        synchronized (session) {
            return new ArrayList<>(session.exchanges);
        }
    }

    public String getHistoryText(String conversationId) {
        List<Exchange> history = getHistory(conversationId);
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Exchange exchange : history) {
            builder.append("用户: ").append(exchange.question).append('\n');
            if (exchange.answer != null && !exchange.answer.trim().isEmpty()) {
                builder.append("助手: ").append(exchange.answer).append('\n');
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    public void addExchange(String conversationId, String question, String answer) {
        if (conversationId == null || question == null) {
            return;
        }
        Session session = sessions.computeIfAbsent(conversationId, key -> new Session());
        session.touch();
        String storedAnswer = answer == null ? "" : answer;
        if (storedAnswer.length() > properties.getMemory().getMaxStoredAnswerChars()) {
            storedAnswer = storedAnswer.substring(0, properties.getMemory().getMaxStoredAnswerChars()) + "...";
        }
        int maxExchanges = properties.getMemory().getMaxExchanges() == null
                ? 6 : Math.max(1, properties.getMemory().getMaxExchanges());
        synchronized (session) {
            session.exchanges.addLast(new Exchange(question, storedAnswer,
                    System.currentTimeMillis()));
            while (session.exchanges.size() > maxExchanges) {
                session.exchanges.removeFirst();
            }
        }
    }

    public void clear(String conversationId) {
        if (conversationId != null) {
            sessions.remove(conversationId);
        }
    }

    public int size() {
        return sessions.size();
    }

    private void cleanupExpired() {
        try {
            long ttlMillis = ttlMinutes() * 60_000L;
            long expireBefore = System.currentTimeMillis() - ttlMillis;
            sessions.entrySet().removeIf(entry -> entry.getValue().lastAccessAt < expireBefore);
        } catch (RuntimeException ignored) {
            // 清理线程不应因单次异常退出。
        }
    }

    private long ttlMinutes() {
        Integer value = properties.getMemory().getTtlMinutes();
        return value == null || value <= 0 ? 60 : value;
    }

    /**
     * 一条问答记录。
     */
    public static final class Exchange {
        private final String question;
        private final String answer;
        private final long timestamp;

        private Exchange(String question, String answer, long timestamp) {
            this.question = question;
            this.answer = answer;
            this.timestamp = timestamp;
        }

        public String getQuestion() {
            return question;
        }

        public String getAnswer() {
            return answer;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    private static final class Session {
        private final Deque<Exchange> exchanges = new ArrayDeque<>();
        private volatile long lastAccessAt = System.currentTimeMillis();

        private void touch() {
            lastAccessAt = System.currentTimeMillis();
        }
    }
}
