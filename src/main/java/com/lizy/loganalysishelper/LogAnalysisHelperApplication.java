package com.lizy.loganalysishelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LogAnalysisHelperApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogAnalysisHelperApplication.class, args);
        System.out.println("智能日志分析助手启动成功！接口地址：http://localhost:8080/api/log/analyze");
    }
}