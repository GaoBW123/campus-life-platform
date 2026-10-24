package com.campus.life.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration // 必须有！告诉 Spring 这是一个配置类
public class CaffeineConfig {

    @Bean // 必须有！告诉 Spring 把这个方法的返回值放到容器里
    public Cache<String, String> caffeineCache() {
        return Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(10000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
    }
}