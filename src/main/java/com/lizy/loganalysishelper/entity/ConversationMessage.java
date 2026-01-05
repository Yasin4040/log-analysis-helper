package com.lizy.loganalysishelper.entity;

import lombok.Data;
import java.util.Date;

/**
 * 单条对话消息实体
 */
@Data
public class ConversationMessage {
    // 角色：user/assistant
    private String role;
    // 消息内容
    private String content;
    // 时间戳
    private Date timestamp;

    // 快捷创建用户消息
    public static ConversationMessage user(String content) {
        ConversationMessage msg = new ConversationMessage();
        msg.setRole("user");
        msg.setContent(content);
        msg.setTimestamp(new Date());
        return msg;
    }

    // 快捷创建AI消息
    public static ConversationMessage assistant(String content) {
        ConversationMessage msg = new ConversationMessage();
        msg.setRole("assistant");
        msg.setContent(content);
        msg.setTimestamp(new Date());
        return msg;
    }
}