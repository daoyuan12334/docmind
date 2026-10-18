package com.docmind.infra.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内缓存适配器（dev / 单机部署可用）。
 * 语义对齐 Redis 适配器：TTL 按条目过期，列表带裁剪，计数器惰性过期。
 */
@Component
@ConditionalOnProperty(name = "docmind.cache.type", havingValue = "local", matchIfMissing = true)
public class LocalCacheAdapter implements CachePort {

    /** 每条的 TTL 由写入方决定，因此用自定义 Expiry 从旁路登记表取。 */
    private final Map<String, Long> ttlNanos = new ConcurrentHashMap<>();

    private final Cache<String, String> kv = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfter(new Expiry<String, String>() {
                @Override
                public long expireAfterCreate(String key, String value, long currentTime) {
                    Long ttl = ttlNanos.get(key);
                    return ttl == null ? Long.MAX_VALUE : ttl;
                }

                @Override
                public long expireAfterUpdate(String key, String value, long currentTime, long currentDuration) {
                    Long ttl = ttlNanos.get(key);
                    return ttl == null ? currentDuration : ttl;
                }

                @Override
                public long expireAfterRead(String key, String value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .removalListener((key, value, cause) -> {
                if (key != null) {
                    ttlNanos.remove(key);
                }
            })
            .build();

    private final Map<String, Deque<String>> lists = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    private static final class Counter {
        final AtomicLong value = new AtomicLong();
        volatile long expireAtMillis;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(kv.getIfPresent(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        ttlNanos.put(key, ttl.toNanos());
        kv.put(key, value);
    }

    @Override
    public void evict(String key) {
        kv.invalidate(key);
        ttlNanos.remove(key);
        lists.remove(key);
        counters.remove(key);
    }

    @Override
    public long increment(String key, Duration ttl) {
        Counter counter = counters.computeIfAbsent(key, k -> {
            Counter c = new Counter();
            c.expireAtMillis = System.currentTimeMillis() + ttl.toMillis();
            return c;
        });
        if (System.currentTimeMillis() > counter.expireAtMillis) {
            counter.value.set(0);
            counter.expireAtMillis = System.currentTimeMillis() + ttl.toMillis();
        }
        return counter.value.incrementAndGet();
    }

    @Override
    public List<String> tail(String key, int count) {
        Deque<String> deque = lists.get(key);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            List<String> all = new ArrayList<>(deque);
            int from = Math.max(0, all.size() - count);
            return new ArrayList<>(all.subList(from, all.size()));
        }
    }

    @Override
    public void append(String key, String value, int maxSize, Duration ttl) {
        Deque<String> deque = lists.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(value);
            while (deque.size() > maxSize) {
                deque.removeFirst();
            }
        }
    }

    @Override
    public void deleteList(String key) {
        lists.remove(key);
    }

    @Override
    public boolean ping() {
        return true;
    }

    /** 定期清理已过期的计数器，避免长期运行内存缓慢增长。 */
    @Scheduled(fixedDelay = 60_000L)
    public void sweepCounters() {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(e -> e.getValue().expireAtMillis < now);
    }
}
