package com.restaurant.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private CacheService cacheService;

    @BeforeEach
    void setup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void get_returns_null_for_missing_key() {
        when(valueOps.get("k")).thenReturn(null);
        assertNull(cacheService.get("k"));
    }

    @Test
    void get_returns_null_for_null_sentinel() {
        when(valueOps.get("k")).thenReturn("NULL");
        assertNull(cacheService.get("k"));
    }

    @Test
    void get_returns_value_when_present() {
        when(valueOps.get("k")).thenReturn("answer");
        assertEquals("answer", cacheService.get("k"));
    }

    @Test
    void set_stores_value_with_ttl_within_10_percent_of_one_hour() {
        cacheService.set("k", "v");
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("k"), eq("v"), ttlCaptor.capture());
        long secs = ttlCaptor.getValue().getSeconds();
        assertTrue(secs >= 3240 && secs <= 3960, "TTL should be 3600 ±10%, got: " + secs);
    }

    @Test
    void setNullSentinel_stores_NULL_with_5_minute_ttl() {
        cacheService.setNullSentinel("k");
        verify(valueOps).set(eq("k"), eq("NULL"), eq(Duration.ofMinutes(5)));
    }
}
