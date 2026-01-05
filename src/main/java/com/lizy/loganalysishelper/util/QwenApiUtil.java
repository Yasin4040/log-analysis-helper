package com.lizy.loganalysishelper.util;

import com.lizy.loganalysishelper.entity.LogAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public class QwenApiUtil {
    // 从配置文件读取大模型信息
    @Value("${qwen.api-key}")
    private String apiKey;
    @Value("${qwen.api-url}")
    private String apiUrl;
    @Value("${qwen.model}")
    private String model;

    // OkHttp客户端（单例，避免重复创建）
    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 分析Java异常日志（移植Python analyze_log_v3逻辑，适配JDK8+Qwen Text格式）
     */
    public LogAnalysisResponse analyzeJavaLog(String exceptionLog) {
        // ========== 第一步：移植Python的输入校验逻辑（优先执行，避免无效调用大模型） ==========
        // 1. 校验输入是否为空/仅空白字符
        if (exceptionLog == null || exceptionLog.trim().isEmpty()) {
            return LogAnalysisResponse.error(400, "错误：输入日志为空，请重新输入");
        }
        // 2. 校验是否为有效Java异常日志（包含java.lang.）
        if (!exceptionLog.contains("java.lang.")) {
            return LogAnalysisResponse.error(400, "错误：请输入有效的Java异常日志");
        }

        // ========== 第二步：构建JDK8兼容的新Prompt模板（完整保留Python Prompt内容） ==========
        // 用+拼接+\\n换行，替换Python的"""文本块；用%s替换Python的{log_text}占位符
        String promptTemplate = "你是Java开发专家，请严格按照以下流程和格式分析内容：\n"
                + "第一步：输入校验（已通过，无需重复）\n"
                + "第二步：日志分析（按以下固定格式输出）\n"
                + "## 错误原因\n"
                + "（异常类型+触发位置（类+方法+行号）+ 核心触发原因）\n"
                + "## 解决方案（分步骤，附带代码示例）\n"
                + "1.  定位文件：UserController.java 第45行\n"
                + "2.  代码修复：在调用username.equals()前添加空值检查\n"
                + "    if (username != null && username.equals(\"admin\")) {\n"
                + "        // 业务逻辑（注释）\n"
                + "    }\n"
                + "3.  验证：重新运行测试用例，确认不再出现NullPointerException\n"
                + "## 预防措施\n"
                + "1.  所有外部输入（如请求参数）必须进行空值校验\n"
                + "2.  使用Optional类或Guava的Preconditions避免空指针\n"
                + "\n"
                + "输入内容：%s\n"
                + "强制要求：\n"
                + "1.  严格遵循上述格式，无任何额外内容\n"
                + "2.  代码示例符合Java规范";
        // 填充日志内容（替换%s为实际异常日志，对应Python的f-string插值）
        String finalPrompt = String.format(promptTemplate, exceptionLog);

        try {
            // ========== 第三步：构建大模型请求参数（保持原有逻辑不变） ==========
            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("model", model);

            // 构建messages参数
            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("role", "user");
            messageMap.put("content", finalPrompt);
            Map<String, Object> inputMap = new HashMap<>();
            inputMap.put("messages", new Object[]{messageMap});
            requestBodyMap.put("input", inputMap);

            // 构建parameters参数（确认text格式）
            Map<String, Object> parametersMap = new HashMap<>();
            parametersMap.put("result_format", "text");
            parametersMap.put("temperature", 0.2);
            parametersMap.put("top_p", 0.7);
            requestBodyMap.put("parameters", parametersMap);

            // ========== 第四步：构建OkHttp请求（保持原有逻辑不变） ==========
            MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
            RequestBody requestBody = RequestBody.create(
                    mediaType,
                    objectMapper.writeValueAsString(requestBodyMap)
            );
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            // ========== 第五步：处理Text格式响应（保持原有安全解析逻辑不变） ==========
            try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return LogAnalysisResponse.error(500, "大模型调用失败：" + response.message());
                }

                String responseBody = null;
                if (response.body() != null) {
                    responseBody = response.body().string();
                }
                if (responseBody == null || responseBody.trim().isEmpty()) {
                    return LogAnalysisResponse.error(500, "大模型响应体为空");
                }

                JsonNode jsonNode = objectMapper.readTree(responseBody);
                if (jsonNode.has("output")) {
                    JsonNode outputNode = jsonNode.get("output");
                    if (outputNode.has("text") && !outputNode.get("text").isNull() && outputNode.get("text").isTextual()) {
                        String analysisResult = outputNode.get("text").asText().trim();
                        return LogAnalysisResponse.success(analysisResult);
                    } else {
                        return LogAnalysisResponse.error(500, "大模型响应缺少text字段，格式异常");
                    }
                } else {
                    return LogAnalysisResponse.error(500, "大模型响应缺少output字段，格式异常");
                }
            }
        } catch (IOException e) {
            return LogAnalysisResponse.error(500, "接口处理异常：" + e.getMessage());
        }
    }
}