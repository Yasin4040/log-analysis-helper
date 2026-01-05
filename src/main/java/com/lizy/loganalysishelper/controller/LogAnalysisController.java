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
     * 智能日志分析接口
     * 接口地址：http://localhost:8080/api/log/analyze
     * 请求方式：POST
     * 请求体：{"exceptionLog":"你的Java异常日志"}
     */
    @PostMapping("/analyze")
    public LogAnalysisResponse analyzeLog(@RequestBody LogAnalysisRequest request) {
        // 参数校验
        if (request.getExceptionLog() == null || request.getExceptionLog().trim().isEmpty()) {
            return LogAnalysisResponse.error(400, "异常日志不能为空");
        }
        // 调用大模型分析日志
        return qwenApiUtil.analyzeJavaLog(request.getExceptionLog());
    }
}