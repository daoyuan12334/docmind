package com.docmind.observe;

import com.docmind.infra.cache.CachePort;
import lombok.RequiredArgsConstructor;
// Spring Boot 4 把健康检查相关类型从 org.springframework.boot.actuate.health
// 迁到了独立的 spring-boot-health 模块：org.springframework.boot.health.contributor。
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 缓存健康检查：直接问自己用的 {@link CachePort} 通不通，而不是问 Redis 的 INFO。
 *
 * <p>为什么不直接用 Spring Boot 自带的 Redis 健康指示器：它是靠解析 {@code INFO} 命令输出实现的
 * （内部走 {@code Properties.load()}）。Windows 版 Redis 的 {@code INFO server} 里会带上形如
 * {@code executable:C:/users/.../redis-server.exe} 的反斜杠路径（下面只用正斜杠书写，原因见备注），
 * {@code Properties.load()} 会把那两个字符当成 Unicode 转义的开头去解析，直接抛
 * {@code IllegalArgumentException: Malformed <backslash>uxxxx encoding.}，
 * 于是连接明明是好的，健康状态却被报成 DOWN。
 *
 * <p><b>备注（Java 的坑）：</b>javac 在词法分析的“Unicode 转义预处理”阶段就会处理反斜杠 + u，
 * 而且<b>不看它在不在注释里</b>。也就是说注释里写 Windows 路径 {@code c:} 后接反斜杠和
 * {@code users} 会直接编译失败（非法 Unicode 转义）。所以这段注释里统一用正斜杠描述该路径。
 *
 * <p>统一走端口自己的 {@code ping()}（底层是真正的 PING 命令），既能绕开这个解析坑，
 * 也更能反映「业务实际依赖的那条链路」是否可用——本地 profile 走进程内缓存时同样有结果。
 * 因此各 profile 里都关掉了 {@code management.health.redis.enabled}，只保留这一个缓存指示器。
 */
@Component("cache")
@RequiredArgsConstructor
public class CacheHealthIndicator implements HealthIndicator {

    private final CachePort cache;

    @Override
    public Health health() {
        boolean alive;
        try {
            alive = cache.ping();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.toString()).build();
        }
        if (!alive) {
            return Health.down().withDetail("error", "ping 未返回 PONG").build();
        }
        return Health.up()
                .withDetail("implementation", cache.getClass().getSimpleName())
                .build();
    }
}
