package com.docmind.infra.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存端口的行为契约——本地实现和 Redis 实现必须都满足。
 *
 * <p>这里测的是 dev 实际在跑的本地适配器（Redis 那套由 RedisCacheAdapterTest 用 mock 覆盖，
 * 真实 Redis 的联调由 RedisCacheIT 在系统装了 Redis 时才启用）。
 *
 * <p>重点在 tail/append 的窗口语义和 increment 的过期语义：
 * 会话记忆取错了条数会直接把上下文喂歪，限流计数不过期会把用户永久封死。
 */
class CachePortContractTest {

    private final CachePort cache = new LocalCacheAdapter();

    @Nested
    @DisplayName("Key-Value")
    class KeyValue {

        @Test
        @DisplayName("写入后能读到，TTL 内不过期")
        void getAfterPut() {
            cache.put("k1", "v1", Duration.ofMinutes(5));
            assertEquals("v1", cache.get("k1").orElseThrow());
        }

        @Test
        @DisplayName("不存在的 key 返回 empty 而不是抛异常")
        void missingKeyIsEmpty() {
            assertTrue(cache.get("nope").isEmpty());
        }

        @Test
        @DisplayName("evict 之后读不到")
        void evictRemoves() {
            cache.put("k2", "v2", Duration.ofMinutes(5));
            cache.evict("k2");
            assertTrue(cache.get("k2").isEmpty());
        }

        @Test
        @DisplayName("TTL 到期后自动失效")
        void expiresAfterTtl() throws Exception {
            cache.put("k3", "v3", Duration.ofMillis(150));
            assertEquals("v3", cache.get("k3").orElseThrow());
            Thread.sleep(400);
            assertTrue(cache.get("k3").isEmpty(), "TTL 到期后应失效");
        }
    }

    @Nested
    @DisplayName("计数器（限流 / 配额）")
    class Counter {

        @Test
        @DisplayName("连续自增返回递增序列，首次为 1")
        void incrementSequence() {
            String key = "rl:user:1";
            assertEquals(1L, cache.increment(key, Duration.ofMinutes(1)));
            assertEquals(2L, cache.increment(key, Duration.ofMinutes(1)));
            assertEquals(3L, cache.increment(key, Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("不同 key 的计数互不干扰")
        void countersAreIsolated() {
            cache.increment("rl:a", Duration.ofMinutes(1));
            cache.increment("rl:a", Duration.ofMinutes(1));
            assertEquals(1L, cache.increment("rl:b", Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("计数器到期后重新从 1 开始（否则用户会被永久限流）")
        void counterResetsAfterTtl() throws Exception {
            String key = "rl:reset";
            cache.increment(key, Duration.ofMillis(150));
            cache.increment(key, Duration.ofMillis(150));
            Thread.sleep(400);
            assertEquals(1L, cache.increment(key, Duration.ofMinutes(1)),
                    "TTL 过期后计数必须归零重新开始");
        }
    }

    @Nested
    @DisplayName("列表窗口（会话记忆）")
    class ListWindow {

        @Test
        @DisplayName("tail 只返回最后 N 条，且保持时间顺序")
        void tailReturnsLastN() {
            String key = "mem:conv:1";
            for (int i = 1; i <= 5; i++) {
                cache.append(key, "msg-" + i, 3, Duration.ofMinutes(10));
            }
            List<String> tail = cache.tail(key, 3);
            assertEquals(List.of("msg-3", "msg-4", "msg-5"), tail, "应取最后 3 条且保持顺序");
        }

        @Test
        @DisplayName("append 超出 maxSize 时裁剪，不会无限增长")
        void appendTrimsToMaxSize() {
            String key = "mem:conv:2";
            for (int i = 1; i <= 10; i++) {
                cache.append(key, "m" + i, 4, Duration.ofMinutes(10));
            }
            assertEquals(4, cache.tail(key, 100).size(), "裁剪后最多保留 maxSize 条");
            assertEquals(List.of("m7", "m8", "m9", "m10"), cache.tail(key, 100));
        }

        @Test
        @DisplayName("请求的条数多于实际存量时，返回全部而不是报错或补空")
        void tailMoreThanSize() {
            String key = "mem:conv:3";
            cache.append(key, "only", 5, Duration.ofMinutes(10));
            assertEquals(List.of("only"), cache.tail(key, 50));
        }

        @Test
        @DisplayName("空列表 tail 返回空集合")
        void tailOnMissingKey() {
            assertTrue(cache.tail("mem:missing", 5).isEmpty());
        }

        @Test
        @DisplayName("deleteList 清空会话记忆")
        void deleteListClears() {
            String key = "mem:conv:4";
            cache.append(key, "x", 5, Duration.ofMinutes(10));
            cache.deleteList(key);
            assertTrue(cache.tail(key, 10).isEmpty());
        }
    }

    @Test
    @DisplayName("ping 在可用时返回 true")
    void pingAlive() {
        assertTrue(cache.ping());
        assertFalse(cache.ping() && false);
    }
}
