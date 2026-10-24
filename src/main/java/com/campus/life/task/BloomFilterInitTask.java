package com.campus.life.task;

import com.campus.life.entity.Shop;
import com.campus.life.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class BloomFilterInitTask implements CommandLineRunner {

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private IShopService shopService;

    @Override
    public void run(String... args) throws Exception {
        log.info("【系统预热】开始初始化商铺布隆过滤器...");

        // 1. 获取或创建布隆过滤器
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");

        // 2. 初始化：预计存入 10万条数据，允许的误差率为 1% (数字越小越准，但越占内存)
        bloomFilter.tryInit(100000L, 0.01);

        // 3. 从 MySQL 查询出所有的商铺 ID
        List<Shop> shopList = shopService.list();
        for (Shop shop : shopList) {
            bloomFilter.add(shop.getId());
        }

        log.info("【系统预热】布隆过滤器加载完毕，共加载 {} 条热点 ID", shopList.size());
    }
}