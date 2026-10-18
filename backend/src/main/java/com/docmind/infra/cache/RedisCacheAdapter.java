package com.docmind.infra.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Redis 适配器。限流用 Lua 脚本保证「自增 + 首次设 TTL」原子性，
 * 会话窗口用 List + LTRIM，避免记忆无限增长拖垮上下文。
 */
@Component
@ConditionalOnProperty(name = "docmind.cache.type", havingValue = "redis")
public class RedisCacheAdapter implements CachePort {

    private static final String INCR_LUA = """
            local v = redis.call('INCR', KEYS[1])
            if v == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return v
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> incrScript;

    public RedisCacheAdapter(StringRedisTemplate redis) {
        this.redis = redis;
        this.incrScript = new DefaultRedisScript<>(INCR_LUA, Long.class);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    @Override
    public void evict(String key) {
        redis.delete(key);
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long v = redis.execute(incrScript, List.of(key), String.valueOf(ttl.toMillis()));
        return v == null ? 0L : v;
    }

    @Override
    public List<String> tail(String key, int count) {
        Long size = redis.opsForList().size(key);
        if (size == null || size == 0) {
            return List.of();
        }
        long start = Math.max(0, size - count);
        List<String> range = redis.opsForList().range(key, start, size - 1);
        return range == null ? List.of() : new ArrayList<>(range);
    }

    @Override
    public void append(String key, String value, int maxSize, Duration ttl) {
        redis.opsForList().rightPush(key, value);
        redis.opsForList().trim(key, -maxSize, -1);
        redis.expire(key, ttl);
    }

    @Override
    public void deleteList(String key) {
        redis.delete(key);
    }

    @Override
    public boolean ping() {
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            return false;
        }
    }
}
