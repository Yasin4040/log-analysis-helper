package com.lizy.loganalysishelper.entity;

import lombok.Data;

@Data
public class LogAnalysisRequest {
    // Java异常日志文本
    private String exceptionLog;
    // 多轮对话会话ID（可选）
    private String sessionId;
}