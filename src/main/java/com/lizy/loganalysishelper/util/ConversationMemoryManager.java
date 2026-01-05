package com.lizy.loganalysishelper.util;

import com.lizy.loganalysishelper.entity.ConversationMessage;
import com.lizy.loganalysishelper.entity.ConversationSession;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对话记忆管理器（单例+内存存储+过期清理）
 */
@Component
public class ConversationMemoryManager {
    // 线程安全的会话存储
    private final Map<String, ConversationSession> sessionMap = new ConcurrentHashMap<>();
    // 会话过期时间（30分钟）
    private static final long SESSION_EXPIRE_MS = 30 * 60 * 1000L;
    // 最大会话数（避免内存溢出）
    private static final int MAX_SESSION_COUNT = 100;
    // 定时清理线程池
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ConversationMemoryManager() {
        // 每分钟清理一次过期会话
        scheduler.scheduleAtFixedRate(this::cleanExpiredSessions, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 获取/创建会话（空ID自动生成）
     */
    public ConversationSession getOrCreateSession(String sessionId) {
        // 生成随机SessionID
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = "SESSION_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000);
        }
        // 会话数超限则移除最早会话
        if (sessionMap.size() >= MAX_SESSION_COUNT) {
            String oldestSession = sessionMap.entrySet().stream()
                    .sorted((e1, e2) -> e1.getValue().getLastActiveTime().compareTo(e2.getValue().getLastActiveTime()))
                    .findFirst().map(Map.Entry::getKey).orElse(null);
            if (oldestSession != null) sessionMap.remove(oldestSession);
        }
        // 创建/获取会话
        return sessionMap.computeIfAbsent(sessionId, id -> {
            ConversationSession session = new ConversationSession();
            session.setSessionId(id);
            session.setLastActiveTime(new Date());
            return session;
        });
    }

    /**
     * 向会话添加消息
     */
    public void addMessageToSession(String sessionId, ConversationMessage message) {
        ConversationSession session = getOrCreateSession(sessionId);
        session.addMessage(message);
        sessionMap.put(sessionId, session);
    }

    /**
     * 清理过期会话
     */
    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        sessionMap.entrySet().removeIf(entry ->
            (now - entry.getValue().getLastActiveTime().getTime()) > SESSION_EXPIRE_MS
        );
    }

    /**
     * 应用关闭时关闭线程池
     */
    public void shutdown() {
        scheduler.shutdown();
    }
}