package com.lizy.loganalysishelper.util;

import com.lizy.loganalysishelper.entity.ConversationMessage;
import com.lizy.loganalysishelper.entity.ConversationSession;
import com.lizy.loganalysishelper.entity.LogAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 通义千问API调用工具类（支持多轮对话+动态校验+分层Prompt）
 * 核心能力：
 * 1. 首次对话强制校验Java异常日志格式
 * 2. 后续追问跳过格式校验，聚焦问题作答
 * 3. 区分首次/追问的Prompt模板，避免回答重复
 * 4. 自动管理会话上下文，保留最近3轮对话
 */
@Component
public class QwenApiUtil {
    // 通义千问API密钥
    @Value("${qwen.api-key}")
    private String apiKey;
    // 通义千问API请求地址（默认：https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation）
    @Value("${qwen.api-url}")
    private String apiUrl;
    // 调用的模型（如：qwen-turbo/qwen-plus）
    @Value("${qwen.model}")
    private String model;

    // OkHttp客户端（全局单例，避免重复创建）
    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)  // 连接超时
            .readTimeout(60, TimeUnit.SECONDS)     // 读取超时
            .writeTimeout(60, TimeUnit.SECONDS)    // 写入超时
            .build();

    // JSON序列化工具（全局单例）
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 注入会话记忆管理器（管理多轮对话上下文）
    @Autowired
    private ConversationMemoryManager conversationMemoryManager;

    /**
     * 多轮对话版Java日志分析核心方法
     * @param exceptionLog 输入内容（首次为异常日志，后续为追问内容）
     * @param sessionId 会话ID（为空时自动生成）
     * @return 结构化的分析响应结果
     */
    public LogAnalysisResponse analyzeJavaLog(String exceptionLog, String sessionId) {
        // ========== 1. 基础输入校验（所有轮次通用） ==========
        if (exceptionLog == null || exceptionLog.trim().isEmpty()) {
            return LogAnalysisResponse.error(400, "错误：输入内容为空，请重新输入");
        }

        // ========== 2. 会话上下文判断（首次/非首次对话） ==========
        ConversationSession session = conversationMemoryManager.getOrCreateSession(sessionId);
        boolean isFirstRound = session.getMessageHistory().isEmpty(); // 会话无历史则为首次

        // ========== 3. 动态格式校验（仅首次对话强制要求异常日志） ==========
        if (isFirstRound && !exceptionLog.contains("java.lang.")) {
            return LogAnalysisResponse.error(
                    400,
                    "错误：首次分析请输入有效的Java异常日志（需包含java.lang.关键字，如java.lang.NullPointerException）"
            );
        }

        // ========== 4. 分层Prompt构建（核心：避免追问重复回答） ==========
        String contextText = session.buildContextText(); // 拼接历史对话上下文
        String promptTemplate;

        // 4.1 首次对话：强制固定格式，输出完整的日志分析
        if (isFirstRound) {
            promptTemplate = contextText +
                    "你是资深Java开发专家，负责分析Java异常日志，请严格按照以下固定格式输出分析结果：\n"
                    + "## 错误原因\n"
                    + "（需包含：异常类型 + 触发位置（类名+方法+行号） + 核心触发原因）\n"
                    + "## 解决方案（分步骤，附带可直接运行的代码示例）\n"
                    + "1. 定位文件：[异常所在文件路径+行号]\n"
                    + "2. 代码修复：[完整的修复代码片段，包含注释]\n"
                    + "3. 验证方法：[如何验证修复生效的具体步骤]\n"
                    + "## 预防措施\n"
                    + "（至少2条可落地的开发规范/编码建议）\n"
                    + "\n"
                    + "待分析的Java异常日志：%s\n"
                    + "强制要求：\n"
                    + "1. 严格遵循上述三级标题格式，无任何额外开场白/结束语\n"
                    + "2. 代码示例符合Java 8+规范，注释清晰\n"
                    + "3. 分析结果必须精准到具体行号和触发原因，禁止泛泛而谈";
        }
        // 4.2 后续追问：聚焦当前问题，禁止重复历史内容
        else {
            promptTemplate = contextText +
                    "你是资深Java开发专家，基于上述历史对话上下文，回答用户的当前追问：\n"
                    + "核心规则：\n"
                    + "1. 优先性：先精准回答当前追问的核心问题，不要重复历史分析的完整内容\n"
                    + "2. 精简性：仅补充与当前问题强相关的历史信息（不超过2句话）\n"
                    + "3. 格式性：无需遵循固定标题格式，用自然语言简洁作答，可附带简短代码示例\n"
                    + "4. 禁止性：绝对禁止重复历史对话中已完整输出的“错误原因/解决方案/预防措施”全文\n"
                    + "\n"
                    + "用户当前追问：%s\n"
                    + "强制要求：回答仅聚焦当前问题，字数控制在200字以内，直击核心";
        }

        // 拼接最终Prompt（替换占位符）
        String finalPrompt = String.format(promptTemplate, exceptionLog);

        try {
            // ========== 5. 构建通义千问API请求参数 ==========
            Map<String, Object> requestBodyMap = new HashMap<>(4);
            requestBodyMap.put("model", model); // 指定调用的模型

            // 构建消息体（用户角色+Prompt内容）
            Map<String, Object> messageMap = new HashMap<>(2);
            messageMap.put("role", "user");
            messageMap.put("content", finalPrompt);

            // 构建输入参数
            Map<String, Object> inputMap = new HashMap<>(1);
            inputMap.put("messages", new Object[]{messageMap});
            requestBodyMap.put("input", inputMap);

            // 构建调用参数（控制回答稳定性）
            Map<String, Object> parametersMap = new HashMap<>(3);
            parametersMap.put("result_format", "text"); // 文本格式输出
            parametersMap.put("temperature", 0.2);     // 低随机性，保证回答稳定
            parametersMap.put("top_p", 0.7);            // 采样策略
            requestBodyMap.put("parameters", parametersMap);

            // ========== 6. 发送HTTP请求 ==========
            MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
            RequestBody requestBody = RequestBody.create(mediaType, objectMapper.writeValueAsString(requestBodyMap));
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey) // 鉴权头
                    .addHeader("Content-Type", "application/json")  // 内容类型
                    .post(requestBody)
                    .build();

            // ========== 7. 处理API响应 ==========
            try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
                // 响应状态码非200时返回错误
                if (!response.isSuccessful()) {
                    String errorMsg = String.format("大模型调用失败，状态码：%d，原因：%s",
                            response.code(), response.message());
                    return LogAnalysisResponse.error(500, errorMsg);
                }

                // 读取响应体（空值校验）
                String responseBody = response.body() != null ? response.body().string() : "";
                if (responseBody.trim().isEmpty()) {
                    return LogAnalysisResponse.error(500, "大模型响应体为空，无分析结果");
                }

                // 解析JSON响应
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                if (jsonNode.has("output") && jsonNode.get("output").has("text")) {
                    String analysisResult = jsonNode.get("output").get("text").asText().trim();

                    // ========== 8. 存储当前对话到会话上下文 ==========
                    conversationMemoryManager.addMessageToSession(sessionId, ConversationMessage.user(exceptionLog));
                    conversationMemoryManager.addMessageToSession(sessionId, ConversationMessage.assistant(analysisResult));

                    // 返回成功结果
                    return LogAnalysisResponse.success(analysisResult);
                } else {
                    return LogAnalysisResponse.error(500, "大模型响应格式异常，未找到output.text字段");
                }
            }
        } catch (IOException e) {
            // 捕获IO异常（网络/序列化失败）
            String errorMsg = String.format("接口处理异常：%s，异常详情：%s",
                    e.getMessage(), e.getCause() != null ? e.getCause().getMessage() : "无");
            return LogAnalysisResponse.error(500, errorMsg);
        } catch (Exception e) {
            // 捕获其他未知异常，避免程序崩溃
            String errorMsg = String.format("未知异常：%s", e.getMessage());
            return LogAnalysisResponse.error(500, errorMsg);
        }
    }
}