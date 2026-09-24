package com.why.fulfillment.commerce.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 只注册一个密码编码器。这里刻意不引入 spring-boot-starter-security：
 * 鉴权在 Gateway 做，本服务只需要 BCrypt 这一个工具类。
 */
@Configuration
public class PasswordEncoderConfiguration {

    /**
     * BCrypt 强度 10。
     *
     * <p>强度每 +1，计算时间翻倍。10 在本机约 50-100ms，是安全性和登录延迟的
     * 常见折中。调高之前先自己压一次登录接口——这是个 CPU 密集操作，
     * 强度 14 的登录接口在几十并发下就会把 CPU 打满。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
