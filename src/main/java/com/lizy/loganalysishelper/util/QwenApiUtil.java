package com.lizy.loganalysishelper.util;

import com.lizy.loganalysishelper.entity.ConversationMessage;
import com.lizy.loganalysishelper.entity.ConversationSession;
import com.lizy.loganalysishelper.entity.LogAnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通义千问API调用工具类（生产级优化版）
 * 核心能力：
 * 1. 多轮对话支持（首次/追问分层Prompt）
 * 2. 动态输入校验（仅首次强制日志格式）
 * 3. 完善日志监控（关键步骤全日志）
 * 4. 网络重试机制（应对临时网络波动）
 * 5. 配置容错（默认值+启动校验）
 * 6. 性能统计（接口调用耗时）
 * 7. Prompt配置化（模板外置，便于修改）
 * 8. 响应结果优化（去冗余、格式化）
 */
@Component
public class QwenApiUtil {
    // 日志记录器（生产级必备）
    private static final Logger log = LoggerFactory.getLogger(QwenApiUtil.class);
    // ========== 配置项（含默认值，避免配置缺失报错） ==========
    @Value("${qwen.api-key:}")
    private String apiKey;

    @Value("${qwen.api-url:https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation}")
    private String apiUrl;

    @Value("${qwen.model:qwen-turbo}")
    private String model;

    // 重试配置
    @Value("${qwen.retry.count:2}")
    private int retryCount;

    @Value("${qwen.retry.delay:1000}")
    private long retryDelay;

    // Prompt模板配置（外置，便于修改，无需改代码）
    @Value("${qwen.prompt.first:你是资深Java开发专家，负责分析Java异常日志，请严格按照以下固定格式输出分析结果：\\n## 错误原因\\n（需包含：异常类型 + 触发位置（类名+方法+行号） + 核心触发原因）\\n## 解决方案（分步骤，附带可直接运行的代码示例）\\n1. 定位文件：[异常所在文件路径+行号]\\n2. 代码修复：[完整的修复代码片段，包含注释]\\n3. 验证方法：[如何验证修复生效的具体步骤]\\n## 预防措施\\n（至少2条可落地的开发规范/编码建议）\\n\\n待分析的Java异常日志：%s\\n强制要求：\\n1. 严格遵循上述三级标题格式，无任何额外开场白/结束语\\n2. 代码示例符合Java 8+规范，注释清晰\\n3. 分析结果必须精准到具体行号和触发原因，禁止泛泛而谈}")
    private String firstRoundPromptTemplate;

    @Value("${qwen.prompt.follow:你是资深Java开发专家，基于上述历史对话上下文，回答用户的当前追问：\\n核心规则：\\n1. 优先性：先精准回答当前追问的核心问题，不要重复历史分析的完整内容\\n2. 精简性：仅补充与当前问题强相关的历史信息（不超过2句话）\\n3. 格式性：无需遵循固定标题格式，用自然语言简洁作答，可附带简短代码示例\\n4. 禁止性：绝对禁止重复历史对话中已完整输出的“错误原因/解决方案/预防措施”全文\\n\\n用户当前追问：%s\\n强制要求：回答仅聚焦当前问题，字数控制在200字以内，直击核心}")
    private String followRoundPromptTemplate;

    // ========== 静态配置 ==========
    // OkHttp客户端（带重试拦截器）
    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true) // 底层连接失败重试
            .build();

    // JSON工具（线程安全，全局单例）
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 冗余换行/空格清理正则
    private static final Pattern REDUNDANT_SPACE_PATTERN = Pattern.compile("\\n{3,}|\\s{2,}");

    // ========== 依赖注入 ==========
    @Autowired
    private ConversationMemoryManager conversationMemoryManager;

    // ========== 启动校验（提前发现配置问题） ==========
    @PostConstruct
    public void validateConfig() {
        if (!StringUtils.hasText(apiKey)) {
            log.error("[QwenApiUtil] 通义千问API密钥未配置（qwen.api-key），请检查application.yml");
            throw new IllegalStateException("通义千问API密钥未配置");
        }
        log.info("[QwenApiUtil] 配置校验通过，模型：{}，重试次数：{}", model, retryCount);
    }

    /**
     * 多轮对话版Java日志分析核心方法（生产级优化）
     *
     * @param exceptionLog 输入内容（首次为异常日志，后续为追问内容）
     * @param sessionId    会话ID（为空时自动生成）
     * @return 结构化的分析响应结果
     */
    public LogAnalysisResponse analyzeJavaLog(String exceptionLog, String sessionId) {
        long startTime = System.currentTimeMillis();
        String traceId = "TRACE_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);

        try {
            log.info("[QwenApiUtil-{}] 开始处理日志分析请求，sessionId：{}，输入内容：{}",
                    traceId, sessionId, truncateContent(exceptionLog));

            // 1. 基础输入校验（所有轮次通用）
            if (!StringUtils.hasText(exceptionLog)) {
                log.warn("[QwenApiUtil-{}] 输入内容为空，返回400错误", traceId);
                return LogAnalysisResponse.error(400, "错误：输入内容为空，请重新输入");
            }
            String cleanInput = exceptionLog.trim();

            // 2. 会话上下文判断（首次/非首次对话）
            ConversationSession session = conversationMemoryManager.getOrCreateSession(sessionId);
            boolean isFirstRound = session.getMessageHistory().isEmpty();
            log.debug("[QwenApiUtil-{}] 对话轮次判断：{}，会话ID：{}", traceId, isFirstRound ? "首次" : "追问", session.getSessionId());

            // 3. 动态格式校验（仅首次对话强制要求异常日志）
            if (isFirstRound && !cleanInput.contains("java.lang.")) {
                log.warn("[QwenApiUtil-{}] 首次对话输入非有效Java异常日志，返回400错误", traceId);
                return LogAnalysisResponse.error(
                        400,
                        "错误：首次分析请输入有效的Java异常日志（需包含java.lang.关键字，如java.lang.NullPointerException）"
                );
            }

            // 4. 分层Prompt构建（配置化模板）
            String contextText = session.buildContextText();
            String promptTemplate = isFirstRound ? firstRoundPromptTemplate : followRoundPromptTemplate;
            String finalPrompt = String.format(promptTemplate, cleanInput);
            // 拼接上下文（仅非首次需要，避免首次上下文为空时冗余）
            if (!isFirstRound) {
                finalPrompt = contextText + finalPrompt;
            }
            log.debug("[QwenApiUtil-{}] 最终Prompt长度：{}字符", traceId, finalPrompt.length());

            // 5. 调用大模型（带重试）
            String analysisResult = callQwenApiWithRetry(finalPrompt, traceId);
            if (!StringUtils.hasText(analysisResult)) {
                log.error("[QwenApiUtil-{}] 大模型返回空结果", traceId);
                return LogAnalysisResponse.error(500, "大模型分析失败，未返回有效结果");
            }

            // 6. 响应结果优化（清理冗余空格/换行）
            String optimizedResult = optimizeResponse(analysisResult);
            log.info("[QwenApiUtil-{}] 大模型分析成功，结果长度：{}字符", traceId, optimizedResult.length());

            // 7. 存储当前对话到会话上下文
            conversationMemoryManager.addMessageToSession(sessionId, ConversationMessage.user(cleanInput));
            conversationMemoryManager.addMessageToSession(sessionId, ConversationMessage.assistant(optimizedResult));

            // 8. 耗时统计
            long costTime = System.currentTimeMillis() - startTime;
            log.info("[QwenApiUtil-{}] 日志分析处理完成，耗时：{}ms，sessionId：{}", traceId, costTime, sessionId);

            return LogAnalysisResponse.success(optimizedResult);

        } catch (Exception e) {
            long costTime = System.currentTimeMillis() - startTime;
            log.error("[QwenApiUtil-{}] 日志分析处理异常，耗时：{}ms，原因：{}",
                    traceId, costTime, e.getMessage(), e);
            return LogAnalysisResponse.error(500, "日志分析异常：" + e.getMessage());
        }
    }

    /**
     * 调用通义千问API（带重试机制）
     *
     * @param prompt  构建好的Prompt
     * @param traceId 追踪ID
     * @return 大模型返回的文本结果
     * @throws IOException 网络/序列化异常
     */
    private String callQwenApiWithRetry(String prompt, String traceId) throws IOException {
        // 构建请求体
        Request request = buildQwenRequest(prompt);
        // 重试逻辑
        int currentRetry = 0;
        while (currentRetry <= retryCount) {
            try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return parseQwenResponse(response, traceId);
                } else {
                    currentRetry++;
                    log.warn("[QwenApiUtil-{}] 大模型调用失败，状态码：{}，重试次数：{}/{}",
                            traceId, response.code(), currentRetry, retryCount);
                    if (currentRetry > retryCount) {
                        throw new IOException("大模型调用失败，状态码：" + response.code() + "，重试次数耗尽");
                    }
                    // 重试延迟
                    TimeUnit.MILLISECONDS.sleep(retryDelay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[QwenApiUtil-{}] 重试被中断", traceId, e);
                throw new IOException("重试被中断", e);
            }
        }
        return "";
    }

    /**
     * 构建通义千问API请求
     *
     * @param prompt Prompt内容
     * @return OkHttp Request
     * @throws IOException 序列化异常
     */
    private Request buildQwenRequest(String prompt) throws IOException {
        // 构建请求参数
        Map<String, Object> requestBodyMap = new HashMap<>(4);
        requestBodyMap.put("model", model);

        Map<String, Object> messageMap = new HashMap<>(2);
        messageMap.put("role", "user");
        messageMap.put("content", prompt);

        Map<String, Object> inputMap = new HashMap<>(1);
        inputMap.put("messages", new Object[]{messageMap});
        requestBodyMap.put("input", inputMap);

        Map<String, Object> parametersMap = new HashMap<>(3);
        parametersMap.put("result_format", "text");
        parametersMap.put("temperature", 0.2);
        parametersMap.put("top_p", 0.7);
        requestBodyMap.put("parameters", parametersMap);

        // 构建请求
        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody requestBody = RequestBody.create(mediaType, objectMapper.writeValueAsString(requestBodyMap));
        return new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();
    }

    /**
     * 解析通义千问API响应
     *
     * @param response 响应对象
     * @param traceId  追踪ID
     * @return 解析后的文本结果
     * @throws IOException 解析异常
     */
    private String parseQwenResponse(Response response, String traceId) throws IOException {
        try (ResponseBody responseBody = response.body()) {
            if (responseBody == null) {
                log.error("[QwenApiUtil-{}] 大模型响应体为空", traceId);
                return "";
            }
            String responseStr = responseBody.string();
            log.debug("[QwenApiUtil-{}] 大模型原始响应：{}", traceId, truncateContent(responseStr));

            JsonNode jsonNode = objectMapper.readTree(responseStr);
            if (jsonNode.has("output") && jsonNode.get("output").has("text")) {
                return jsonNode.get("output").get("text").asText().trim();
            } else {
                log.error("[QwenApiUtil-{}] 大模型响应格式异常，无output.text字段，响应：{}", traceId, responseStr);
                throw new IOException("大模型响应格式异常，未找到output.text字段");
            }
        }
    }

    /**
     * 优化响应结果（清理冗余空格/换行）- 兼容JDK 8+，无Lambda类型问题
     *
     * @param response 原始响应结果
     * @return 优化后的结果
     */
    private String optimizeResponse(String response) {
        // 1. 空值校验
        if (!StringUtils.hasText(response)) {
            return "";
        }

        // 2. 传统Matcher循环替换（避免Lambda类型推断问题）
        Matcher matcher = REDUNDANT_SPACE_PATTERN.matcher(response);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String matchedStr = matcher.group();
            // 替换规则：连续3+换行→2个换行，连续2+空格→1个空格
            String replacement = matchedStr.startsWith("\n") ? "\n\n" : " ";
            // 替换并追加到结果（避免正则特殊字符转义问题）
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        // 追加剩余未匹配的内容
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 截断超长内容（避免日志刷屏）
     *
     * @param content 原始内容
     * @return 截断后的内容（最多200字符）
     */
    private String truncateContent(String content) {
        if (content == null || content.length() <= 200) {
            return content;
        }
        return content.substring(0, 200) + "...[内容过长，已截断]";
    }
}