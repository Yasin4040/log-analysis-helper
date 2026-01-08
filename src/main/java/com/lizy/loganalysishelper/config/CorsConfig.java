package com.lizy.loganalysishelper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 1. 允许的前端源（生产环境建议写具体域名，如https://xxx.com）
        config.addAllowedOriginPattern("*"); 
        // 2. 允许携带Cookie等凭证（前后端都开启才有效）
        config.setAllowCredentials(true);
        // 3. 允许的请求方法：必须显式包含 OPTIONS
        config.addAllowedMethod("*"); // 等价于 GET、POST、PUT、DELETE、OPTIONS 等
        // 4. 允许的请求头：所有头都允许
        config.addAllowedHeader("*");
        // 5. 允许前端获取的响应头（可选）
        config.addExposedHeader("*");
        // 6. 预检请求缓存时间：3600秒=1小时，避免重复发送OPTIONS请求
        config.setMaxAge(3600L);

        // 配置跨域规则生效的路径：所有接口
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}