package com.campus.life.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.campus.life.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.campus.life.utils.RedisConstants.*;

@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) throws InterruptedException {
        Thread.sleep(500);
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        String jsonRedisData = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key,jsonRedisData);
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix + id.toString();
        //从redis查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果不为null或者""，直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //如果为""返回redis存储的null
        if(json!=null){
            return null;
        }
        //如果为null，查询数据库
        R r = dbFallback.apply(id);
        //没查到，商品不存在则redis加入null
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //查到，加入redis缓存
        this.set(key,r,time,unit);
        return r;
    }

    private final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,String lockPrefix,ID id, Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix + id.toString();
        //从redis查询商品缓存
        String redisDataJson = stringRedisTemplate.opsForValue().get(key);
        // 【魔改核心】：如果 Redis 里没数据
        if(StrUtil.isBlank(redisDataJson)){
            // 1. 因为外面有布隆过滤器保护，走到这里说明绝对是合法的“冷数据”第一次被访问！
            // 2. 既然是第一次，我们就加个互斥锁，去数据库查出来并给它设置逻辑过期时间（完成动态预热）
            String lockKey = "lock:cache:" + key;
            R dbData = null;
            try {
                boolean isLock = tryLock(lockKey);
                if (!isLock) {
                    // 如果没抢到锁，说明有别的线程正在给这个冷数据做预热，
                    // 稍微休眠一下重试，或者为了极致性能直接返回一个降级提示（比如稍后重试）
                    Thread.sleep(50);
                    return queryWithLogicalExpire(keyPrefix, lockPrefix,id, type, dbFallback, time, unit); // 递归重试
                }
                // 抢到锁了，去数据库查！
                dbData = dbFallback.apply(id);
                if (dbData != null) {
                    // 查到了，写入 Redis，正式赋予它“热点数据”的身份！
                    this.setWithLogicalExpire(key, dbData, time, unit);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }
            return dbData;
        }
        //如果命中,json反序列化为对象
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonObject,type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //未过期，直接返回
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //过期，需要重建缓存
        String lockKey = lockPrefix + id;
        boolean isLock = tryLock(lockKey);
        //获取锁成功
        if(isLock){
            //DoubleCheck检查redis中缓存是否已经存在，存在则直接返回
            String json = stringRedisTemplate.opsForValue().get(key);

            RedisData redisData1 = JSONUtil.toBean(json, RedisData.class);
            JSONObject jsonObject1 = (JSONObject) redisData1.getData();
            R r1 = JSONUtil.toBean(jsonObject1,type);
            if (StrUtil.isNotBlank(json)&&redisData1.getExpireTime().isAfter(LocalDateTime.now())){
                unLock(lockKey);
                return r1;
            }
            //开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r2 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r2,time,unit);
                }catch (Exception e){
                    throw new RuntimeException();
                }finally {
                    unLock(lockKey);
                }
            });
        }
        return r;
    }

    public <R,ID> R queryWithMutex(String keyPrefix ,String lockPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix + id.toString();
        //从redis查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果不为null或者""，直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //如果为""返回redis存储的null
        if(json!=null){
            return null;
        }
        R r = null;
        try {
            //如果为null，尝试获取锁失败，休眠后重新尝试
            if(!tryLock(lockPrefix + id)){
                Thread.sleep(50);
                return queryWithMutex(keyPrefix,lockPrefix,id,type,dbFallback,time,unit);
            }

            //DoubleCheck检查redis中缓存是否已经存在，存在则直接返回
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)){
                return JSONUtil.toBean(json, type);
            }

            //如果为null，尝试获取锁成功，查询数据库
            r = dbFallback.apply(id);
            //没查到，商品不存在则redis加入null
            if(r==null){
                stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //查到，加入redis缓存
            this.set(key, r, time, unit);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unLock(lockPrefix + id);
        }
        return r;
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
