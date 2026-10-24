package com.campus.life.service.impl;

import ch.qos.logback.classic.spi.EventArgUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.campus.life.config.RedisPubSubConfig;
import com.campus.life.dto.Result;
import com.campus.life.entity.Shop;
import com.campus.life.mapper.ShopMapper;
import com.campus.life.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.life.utils.CacheClient;
import com.campus.life.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.*;

import static com.campus.life.utils.RedisConstants.*;

/** 服务实现类 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private Cache<String, String> caffeineCache;
    @Autowired
    private RedissonClient redissonClient;

    public static final String CACHE_SHOP_KEY = "cache:shop:";

    /**
     * 【魔改后的高可用查询：L1 + L2 + 布隆防穿透 + 逻辑过期防击穿】
     */
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY,LOCK_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //逻辑过期解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        if(shop ==null)
//            return Result.fail("店铺不存在");

        String key = CACHE_SHOP_KEY + id;

        // 🛡️ 关卡 1：布隆过滤器拦截恶意穿透
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");
        if (!bloomFilter.contains(id)) {
            log.warn("【前置防御】检测到非法或冷门 ID: {"+ id + "}，直接拦截！");
            return Result.fail("店铺不存在！");
        }

        // 🚀 关卡 2：查询本地 Caffeine 缓存 (L1 级)
        String shopJson = caffeineCache.getIfPresent(key);
        if (shopJson != null) {
            log.info("【L1 命中】直接返回 Caffeine 本地缓存！速度飞起！");
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }

        // 🏢 关卡 3：查询 Redis 分布式缓存 (L2 级)，并兜底数据库
        // 这里复用我们之前写好的、自带“互斥锁+异步重建”的无敌工具类
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, LOCK_SHOP_KEY,
                id,
                Shop.class,
                this::getById, // 数据库回调
                30L, TimeUnit.MINUTES
        );

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        // 📦 关卡 4：将查到的最新数据回填到本地 Caffeine 缓存中
        caffeineCache.put(key, JSONUtil.toJsonStr(shop));

        return Result.ok(shop);
    }

    /**
     * 【魔改后的高可用更新：延迟双删思想 + 广播通知】
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺id不能为空");
        }
        // 1. 更新 MySQL 数据库
        updateById(shop);
        // 2. 删除 Redis 缓存 (L2)
        String shopKey = CACHE_SHOP_KEY +id;
        stringRedisTemplate.delete(shopKey);
        // 3. 清理自己的本地 Caffeine 缓存 (L1)
        caffeineCache.invalidate(shopKey);
        // 4. 📢 核心动作：广播告诉集群里的其他兄弟节点，赶紧清理他们本地的脏数据！
        stringRedisTemplate.convertAndSend(RedisPubSubConfig.SYNC_TOPIC, shopKey);
        return Result.ok();
    }
}
