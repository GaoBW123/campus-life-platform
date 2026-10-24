package com.campus.life.listener;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CaffeineSyncListener implements MessageListener {

    @Autowired
    private Cache<String, String> caffeineCache;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // 当别的服务器发来消息时，这里会触发
        String invalidKey = new String(message.getBody());
        log.info("【集群同步】收到缓存失效广播，准备清理本地 Caffeine，Key: {}", invalidKey);
        // 无情清理本地旧数据
        caffeineCache.invalidate(invalidKey);
    }
}