// 日志分析响应实体
package com.lizy.loganalysishelper.entity;

import lombok.Data;

@Data
public class LogAnalysisResponse {
    // 响应码：200成功，400参数错误，500服务器错误
    private Integer code;
    // 响应信息
    private String msg;
    // 分析结果（对应Jupyter迭代3的输出）
    private String analysisResult;

    // 静态工厂方法：快速构建响应
    public static LogAnalysisResponse success(String analysisResult) {
        LogAnalysisResponse response = new LogAnalysisResponse();
        response.setCode(200);
        response.setMsg("分析成功");
        response.setAnalysisResult(analysisResult);
        return response;
    }

    public static LogAnalysisResponse error(Integer code, String msg) {
        LogAnalysisResponse response = new LogAnalysisResponse();
        response.setCode(code);
        response.setMsg(msg);
        return response;
    }
}