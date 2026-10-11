package com.smartcampus.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的分布式互斥锁。
 * <p>
 * 设计要点（也是缓存击穿场景下的关键）：
 * <ol>
 *   <li><b>SETNX + 过期时间</b>：使用 {@code SET key value NX EX seconds} 一条命令完成
 *       「不存在则设置」与「设置过期时间」，保证原子性，同时避免持有者宕机导致的死锁；</li>
 *   <li><b>唯一 token 标识持有者</b>：value 存放 UUID，释放锁时校验，避免误删他人持有的锁；</li>
 *   <li><b>Lua 脚本原子释放</b>：把「比较 value + 删除 key」放在 Lua 中执行，
 *       Redis 单线程模型保证其原子性，杜绝「校验通过后锁恰好过期、随后删掉他人锁」的经典问题；</li>
 *   <li>使用 {@link StringRedisTemplate} 而非自定义序列化器的 RedisTemplate，
 *       保证 key/value 以原始字符串存储，Lua 脚本中可以直接比较。</li>
 * </ol>
 */
@Component
@Slf4j
public class RedisLock {

    /**
     * 释放锁的 Lua 脚本：仅当锁的持有者仍是自己时才删除
     */
    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试获取锁，获取成功返回持有者标识（用于释放），失败返回 null
     *
     * @param lockKey       锁的 key
     * @param expireSeconds 锁的自动过期时间（秒），防止持有者宕机造成死锁
     * @return 持有者标识；获取失败返回 null
     */
    public String tryLock(String lockKey, long expireSeconds) {
        String token = UUID.randomUUID().toString();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, expireSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success) ? token : null;
    }

    /**
     * 释放锁：通过 Lua 脚本校验持有者，保证「判断 + 删除」的原子性
     *
     * @param lockKey 锁的 key
     * @param token   加锁时返回的持有者标识
     */
    public void unlock(String lockKey, String token) {
        if (token == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            // 释放失败不影响业务，锁会在过期时间到达后自动释放
            log.warn("释放分布式锁失败，lockKey = {}", lockKey, e);
        }
    }
}
