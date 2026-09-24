package com.why.fulfillment.commerce.common;

import com.why.fulfillment.commerce.auth.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final String[] allowedOrigins;

    public WebMvcConfiguration(CurrentUserArgumentResolver currentUserArgumentResolver,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${commerce.cors.allowed-origins:http://localhost:5173}")
                               String[] allowedOrigins) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /**
     * 开发期跨域。
     *
     * <p>前端走 Vite 代理时其实用不到这段——留着是为了直连 18084 调试。
     * 用 allowedOrigins 白名单而不是 "*"：虽然这里没用 Cookie，
     * 但把通配符写进代码是个很容易被后来者复制到带凭证接口上的坏样板。</p>
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
