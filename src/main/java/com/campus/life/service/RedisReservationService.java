package com.campus.life.service;

import com.campus.life.entity.VoucherOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class RedisReservationService {
    private static final DefaultRedisScript<Long> RESERVE = script("seckill.lua");
    private static final DefaultRedisScript<Long> RELEASE = script("release_reservation.lua");
    private final StringRedisTemplate redis;

    public RedisReservationService(StringRedisTemplate redis) { this.redis = redis; }

    public Long reserve(VoucherOrder order) {
        return redis.execute(RESERVE, Collections.emptyList(), order.getVoucherId().toString(),
                order.getUserId().toString(), order.getId().toString());
    }

    public void release(Long orderId, Long voucherId, Long userId) {
        Long result = redis.execute(RELEASE, Collections.emptyList(), voucherId.toString(),
                userId.toString(), orderId.toString());
        if (result == null || result < 0) {
            throw new IllegalStateException("预占补偿未完成：Redis 库存不存在或没有返回结果，orderId=" + orderId);
        }
    }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name));
        script.setResultType(Long.class);
        return script;
    }
}
