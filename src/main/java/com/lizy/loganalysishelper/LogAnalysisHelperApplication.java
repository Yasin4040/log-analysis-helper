package com.lizy.loganalysishelper;

import com.lizy.loganalysishelper.util.ConversationMemoryManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class LogAnalysisHelperApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(LogAnalysisHelperApplication.class, args);
        System.out.println("智能日志分析助手启动成功！接口地址：http://localhost:8080/api/log/analyze");

        // 注册JVM关闭钩子，清理定时任务
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ConversationMemoryManager memoryManager = context.getBean(ConversationMemoryManager.class);
            memoryManager.shutdown();
            System.out.println("会话管理器定时任务已关闭");
        }));
    }
}