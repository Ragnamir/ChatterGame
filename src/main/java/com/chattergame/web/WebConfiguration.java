package com.chattergame.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Регистрация резолвера `AuthContext` в MVC. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final AuthContextResolver authContextResolver;

    public WebConfiguration(AuthContextResolver authContextResolver) {
        this.authContextResolver = authContextResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authContextResolver);
    }
}
