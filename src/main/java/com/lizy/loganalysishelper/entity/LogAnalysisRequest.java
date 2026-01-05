// 日志分析请求实体
package com.lizy.loganalysishelper.entity;

import lombok.Data;

@Data
public class LogAnalysisRequest {
    // Java异常日志文本（核心入参）
    private String exceptionLog;
}