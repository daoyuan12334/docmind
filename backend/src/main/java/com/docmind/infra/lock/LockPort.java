package com.docmind.infra.lock;

import java.time.Duration;

/** 分布式锁端口。生产用 Redis SETNX，开发退化为 JVM 内锁。 */
public interface LockPort {

    boolean tryLock(String key, Duration ttl);

    void unlock(String key);
}
