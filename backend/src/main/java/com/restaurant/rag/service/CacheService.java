package com.restaurant.rag.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class CacheService {

    private final StringRedisTemplate redisTemplate;

    public String get(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || "NULL".equals(value)) return null;
        return value;
    }

    /**
     * 写缓存，TTL 随机抖动 ±10% 防雪崩（3240~3960 秒）
     */
    public void set(String key, String value) {
        long jitter = ThreadLocalRandom.current().nextLong(-360, 361);
        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(3600 + jitter));
    }

    /**
     * 写空值哨兵防穿透，TTL 5 分钟
     */
    public void setNullSentinel(String key) {
        redisTemplate.opsForValue().set(key, "NULL", Duration.ofMinutes(5));
    }
}
