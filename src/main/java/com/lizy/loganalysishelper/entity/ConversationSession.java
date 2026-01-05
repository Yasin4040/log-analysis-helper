package com.lizy.loganalysishelper.entity;

import lombok.Data;
import java.util.LinkedList;
import java.util.List;
import java.util.Date;

/**
 * 对话会话实体（存储单会话的历史消息）
 */
@Data
public class ConversationSession {
    // 会话ID
    private String sessionId;
    // 对话历史（LinkedList高效截取最近N条）
    private List<ConversationMessage> messageHistory = new LinkedList<>();
    // 最后交互时间（用于过期清理）
    private Date lastActiveTime;
    // 最大保留轮数（1轮=用户提问+AI回答）
    private static final int MAX_ROUND = 3;

    /**
     * 添加消息并自动截取最近3轮
     */
    public void addMessage(ConversationMessage message) {
        this.messageHistory.add(message);
        this.lastActiveTime = new Date();
        trimToMaxRound();
    }

    /**
     * 截取最近3轮对话（每轮2条消息，共6条）
     */
    private void trimToMaxRound() {
        int maxMsgCount = MAX_ROUND * 2;
        if (messageHistory.size() > maxMsgCount) {
            this.messageHistory = new LinkedList<>(
                messageHistory.subList(messageHistory.size() - maxMsgCount, messageHistory.size())
            );
        }
    }

    /**
     * 构建上下文文本（拼接至Prompt）
     */
    public String buildContextText() {
        if (messageHistory.isEmpty()) return "";
        StringBuilder context = new StringBuilder("【历史对话上下文】\n");
        for (ConversationMessage msg : messageHistory) {
            String role = "user".equals(msg.getRole()) ? "用户：" : "AI分析：";
            context.append(role).append(msg.getContent()).append("\n");
        }
        context.append("【当前分析请求】\n");
        return context.toString();
    }
}