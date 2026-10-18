package com.docmind.infra.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真·Redis 联调：验证 Lua 限流脚本、List 窗口、TTL 这些「mock 测不出来」的东西。
 *
 * <p>只在环境变量 REDIS_HOST 存在时启用，所以没装 Redis 的机器上不会让构建变红。
 *
 * <p>跑法：
 * <pre>
 *   redis-server.exe --port 6379 &amp;
 *   REDIS_HOST=127.0.0.1 ../tool/mvn.sh test -Dtest=RedisCacheIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test-redis")
@EnabledIfEnvironmentVariable(named = "REDIS_HOST", matches = ".+")
class RedisCacheIT {

    @Autowired
    private StringRedisTemplate redis;

    private CachePort cache;

    /**
     * 必须在注入之后再构造适配器：字段初始化早于 Spring 注入，
     * 写成 final 字段会把 null 塞进去，ping 会被 catch 吞掉变成 false，非常难查。
     */
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        cache = new RedisCacheAdapter(redis);
    }

    @Test
    @DisplayName("ping 通真实 Redis")
    void ping() {
        assertTrue(cache.ping(), "连不上 Redis，检查 REDIS_HOST/REDIS_PORT");
    }

    @Test
    @DisplayName("Lua 限流脚本真能执行：连续自增，且首次写入时设上 TTL")
    void luaRateLimitScript() {
        String key = "it:rl:" + System.nanoTime();
        assertEquals(1L, cache.increment(key, Duration.ofMinutes(1)));
        assertEquals(2L, cache.increment(key, Duration.ofMinutes(1)));
        assertEquals(3L, cache.increment(key, Duration.ofMinutes(1)));

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl, "首次自增应把 TTL 设上");
        assertTrue(ttl > 0 && ttl <= 60, "TTL 应在 0~60 秒之间，实际 " + ttl);
        cache.evict(key);
    }

    @Test
    @DisplayName("TTL 单位是毫秒（PEXPIRE），传秒会让限流窗口缩水 1000 倍")
    void ttlUsesMilliseconds() {
        String key = "it:ttl:" + System.nanoTime();
        cache.increment(key, Duration.ofMillis(3000));
        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl >= 1 && ttl <= 3, "3 秒 TTL，实测 " + ttl + " 秒");
        cache.evict(key);
    }

    @Test
    @DisplayName("会话记忆窗口：写 5 条取 3 条，顺序保持时间序")
    void conversationWindow() {
        String key = "it:mem:" + System.nanoTime();
        for (int i = 1; i <= 5; i++) {
            cache.append(key, "msg-" + i, 10, Duration.ofMinutes(5));
        }
        List<String> tail = cache.tail(key, 3);
        assertEquals(List.of("msg-3", "msg-4", "msg-5"), tail);
        cache.deleteList(key);
        assertTrue(cache.tail(key, 10).isEmpty());
    }

    @Test
    @DisplayName("append 超过 maxSize 时裁剪到尾部（LTRIM 负数下标）")
    void appendTrims() {
        String key = "it:trim:" + System.nanoTime();
        for (int i = 1; i <= 8; i++) {
            cache.append(key, "m" + i, 4, Duration.ofMinutes(5));
        }
        assertEquals(4, cache.tail(key, 100).size());
        assertEquals(List.of("m5", "m6", "m7", "m8"), cache.tail(key, 100));
        cache.deleteList(key);
    }

    @Test
    @DisplayName("put/get/evict 基本读写")
    void basicReadWrite() {
        String key = "it:kv:" + System.nanoTime();
        cache.put(key, "hello", Duration.ofMinutes(1));
        assertEquals("hello", cache.get(key).orElseThrow());
        cache.evict(key);
        assertTrue(cache.get(key).isEmpty());
    }
}
