package com.docmind.infra.lock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 锁适配器：Redis 模式下 value 存持有者标识，避免误删他人锁。 */
@Component
public class LockAdapter implements LockPort {

    private final StringRedisTemplate redis;
    private final Map<String, Long> localLocks = new ConcurrentHashMap<>();
    private final Map<String, String> localOwners = new ConcurrentHashMap<>();
    private final String nodeId = UUID.randomUUID().toString();

    public LockAdapter(org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisProvider,
                       org.springframework.core.env.Environment env) {
        boolean useRedis = "redis".equalsIgnoreCase(env.getProperty("docmind.cache.type", "local"));
        this.redis = useRedis ? redisProvider.getIfAvailable() : null;
    }

    @Override
    public boolean tryLock(String key, Duration ttl) {
        if (redis != null) {
            Boolean ok = redis.opsForValue().setIfAbsent("lock:" + key, nodeId, ttl);
            return Boolean.TRUE.equals(ok);
        }
        long now = System.currentTimeMillis();
        Long expireAt = localLocks.get(key);
        if (expireAt != null && expireAt > now) {
            return false;
        }
        localLocks.put(key, now + ttl.toMillis());
        localOwners.put(key, nodeId);
        return true;
    }

    @Override
    public void unlock(String key) {
        if (redis != null) {
            String owner = redis.opsForValue().get("lock:" + key);
            if (nodeId.equals(owner)) {
                redis.delete("lock:" + key);
            }
            return;
        }
        if (nodeId.equals(localOwners.get(key))) {
            localLocks.remove(key);
            localOwners.remove(key);
        }
    }
}
