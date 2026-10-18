package com.docmind.infra.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 缓存端口。生产用 Redis，开发用进程内 Caffeine。
 * 抽象出来是为了让会话记忆、限流、检索缓存等业务代码与具体中间件解耦。
 */
public interface CachePort {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);

    void evict(String key);

    /** 原子自增，返回自增后的值；首次写入时设置 TTL。用于限流与配额。 */
    long increment(String key, Duration ttl);

    /** 取列表尾部若干元素（会话记忆用）。 */
    List<String> tail(String key, int count);

    /** 追加到列表尾并裁剪到 maxSize，同时刷新 TTL。 */
    void append(String key, String value, int maxSize, Duration ttl);

    void deleteList(String key);

    /** 健康检查：用于 /actuator/health 的自定义指示器。 */
    boolean ping();
}
