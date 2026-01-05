package com.lizy.loganalysishelper.controller;

import com.lizy.loganalysishelper.entity.LogAnalysisRequest;
import com.lizy.loganalysishelper.entity.LogAnalysisResponse;
import com.lizy.loganalysishelper.util.QwenApiUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/log")
public class LogAnalysisController {

    @Autowired
    private QwenApiUtil qwenApiUtil;

    /**
     * 智能日志分析接口（支持多轮对话）
     * 请求体：{"exceptionLog":"日志内容", "sessionId":"会话ID"}
     */
    @PostMapping("/analyze")
    public LogAnalysisResponse analyzeLog(@RequestBody LogAnalysisRequest request) {
        if (request.getExceptionLog() == null || request.getExceptionLog().trim().isEmpty()) {
            return LogAnalysisResponse.error(400, "异常日志不能为空");
        }
        // 传递sessionId支持多轮对话
        return qwenApiUtil.analyzeJavaLog(request.getExceptionLog(), request.getSessionId());
    }
}