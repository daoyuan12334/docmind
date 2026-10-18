package com.docmind.infra.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 适配器的调用契约（用 mock 验证「我们怎么写 Redis」，不依赖真实服务）。
 *
 * <p>值得测的都是容易写错的地方：
 * <ul>
 *   <li>tail 的窗口下标——Redis 的 range 是闭区间，写错一个 1 就少一条</li>
 *   <li>increment 的 TTL 单位——Lua 用 PEXPIRE，传秒会变成 1/1000 的窗口</li>
 *   <li>append 的裁剪方向——LTRIM 保留尾部要传负数</li>
 *   <li>ping 吞掉异常——Redis 挂了不能让健康检查接口 500</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisCacheAdapterTest {

    @Mock
    private StringRedisTemplate redis;

    private RedisCacheAdapter adapter() {
        return new RedisCacheAdapter(redis);
    }

    @Test
    @DisplayName("put：以秒级 TTL 写入（Spring 的 set(k,v,ttl) 语义）")
    void putUsesTtl() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        adapter().put("k", "v", Duration.ofMinutes(10));

        verify(ops).set("k", "v", Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("increment：走 Lua 脚本，TTL 必须转成毫秒（PEXPIRE 单位）")
    void incrementUsesMillis() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(7L);

        long v = adapter().increment("rl:u:1", Duration.ofSeconds(60));

        assertEquals(7L, v);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> arg = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<Long>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(script.capture(), keys.capture(), arg.capture());
        assertEquals(List.of("rl:u:1"), keys.getValue());
        assertEquals("60000", arg.getValue(), "PEXPIRE 需要毫秒，传秒会让限流窗口缩短 1000 倍");
        assertTrue(script.getValue().getScriptAsString().contains("PEXPIRE"));
    }

    @Test
    @DisplayName("increment：Redis 返回 null 时降级为 0，不抛 NPE")
    void incrementHandlesNull() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(null);
        assertEquals(0L, adapter().increment("rl:u:2", Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("tail：取末尾 N 条，range 用闭区间 [size-N, size-1]")
    void tailWindow() {
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(ops);
        when(ops.size("mem")).thenReturn(5L);
        when(ops.range("mem", 2L, 4L)).thenReturn(List.of("c", "d", "e"));

        List<String> tail = adapter().tail("mem", 3);

        assertEquals(List.of("c", "d", "e"), tail);
        verify(ops).range("mem", 2L, 4L);
    }

    @Test
    @DisplayName("tail：要的条数超过存量时从 0 开始，不越界")
    void tailClampsStart() {
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(ops);
        when(ops.size("mem")).thenReturn(2L);
        when(ops.range(eq("mem"), anyLong(), anyLong())).thenReturn(List.of("a", "b"));

        adapter().tail("mem", 50);

        verify(ops).range("mem", 0L, 1L);
    }

    @Test
    @DisplayName("tail：列表不存在时返回空集合，不调 range")
    void tailOnEmptyList() {
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(ops);
        when(ops.size("mem")).thenReturn(0L);

        assertTrue(adapter().tail("mem", 5).isEmpty());
        verify(ops, org.mockito.Mockito.never()).range(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("append：右推 + 保留尾部 maxSize 条 + 刷新 TTL")
    void appendPushesTrimsAndExpires() {
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(ops);

        adapter().append("mem", "hello", 8, Duration.ofHours(2));

        verify(ops).rightPush("mem", "hello");
        verify(ops).trim("mem", -8, -1);
        verify(redis).expire("mem", Duration.ofHours(2));
    }

    @Test
    @DisplayName("evict / deleteList 都走 delete")
    void deleteOperations() {
        adapter().evict("k");
        adapter().deleteList("mem");
        verify(redis).delete("k");
        verify(redis).delete("mem");
    }

    @Test
    @DisplayName("ping：Redis 不可达时返回 false 且不抛异常（否则 health 端点会 500）")
    void pingSwallowsException() {
        when(redis.getConnectionFactory()).thenThrow(new RuntimeException("connection refused"));
        assertFalse(adapter().ping());
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> anyList() {
        return any(List.class);
    }
}
